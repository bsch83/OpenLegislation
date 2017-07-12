package gov.nysenate.openleg.processor.sobi;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.eventbus.EventBus;
import gov.nysenate.openleg.config.Environment;
import gov.nysenate.openleg.dao.base.LimitOffset;
import gov.nysenate.openleg.dao.base.SortOrder;
import gov.nysenate.openleg.dao.sourcefiles.SourceFileFsDao;
import gov.nysenate.openleg.dao.sourcefiles.SourceFileRefDao;
import gov.nysenate.openleg.dao.sourcefiles.sobi.SobiFragmentDao;
import gov.nysenate.openleg.model.process.DataProcessAction;
import gov.nysenate.openleg.model.process.DataProcessUnit;
import gov.nysenate.openleg.model.process.DataProcessUnitEvent;
import gov.nysenate.openleg.model.sourcefiles.SourceFile;
import gov.nysenate.openleg.model.sourcefiles.SourceType;
import gov.nysenate.openleg.model.sourcefiles.sobi.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * This SobiProcessService implementation processes every type of sobi fragment.
 */
@Service
public class ManagedSobiProcessService implements SobiProcessService {
    private static final Logger logger = LoggerFactory.getLogger(ManagedSobiProcessService.class);

    private static final Pattern patchTagPattern = Pattern.compile("^\\s*</?PATCH>\\s*$");

    @Autowired private List<SourceFileFsDao> sourceFileFsDaos;

    @Autowired
    private SourceFileRefDao sourceFileRefDao;
    @Autowired
    private SobiFragmentDao sobiFragmentDao;
    @Autowired
    private EventBus eventBus;
    @Autowired
    private Environment env;

    /**
     * Map of source file types to daos.
     */
    private ImmutableMap<SourceType, SourceFileFsDao> sourceFileDaoMap;

    /** --- Processor Dependencies --- */

    @Autowired private List<SobiProcessor> sobiProcessors;

    /**
     * Register processors to handle a specific SobiFragment via this mapping.
     */
    private ImmutableMap<SobiFragmentType, SobiProcessor> processorMap;

    @PostConstruct
    protected void init() {
        eventBus.register(this);

        processorMap = Maps.uniqueIndex(sobiProcessors, SobiProcessor::getSupportedType);

        sourceFileDaoMap = Maps.uniqueIndex(sourceFileFsDaos, SourceFileFsDao::getSourceType);
    }

    /** --- Implemented Methods --- */

    /**
     * {@inheritDoc}
     */
    @Override
    public int collate() {
        return collateSourceFiles();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int ingest() {
        return processPendingFragments(SobiProcessOptions.builder().build());
    }

    @Override
    public String getCollateType() {
        return "sobi file";
    }

    @Override
    public String getIngestType() {
        return "sobi fragment";
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int collateSourceFiles() {
        try {
            int totalCollated = 0;
            List<SobiFile> newSources;
            do {
                newSources = getIncomingSourceFiles();
                for (SourceFile sourceFile : newSources) {
                    collateSourceFile(sourceFile);
                    totalCollated++;
                }
            } while (!newSources.isEmpty() && env.isProcessingEnabled());
            return totalCollated;
        } catch (IOException ex) {
            String errMessage = "Error encountered during collation of source files.";
            throw new DataIntegrityViolationException(errMessage, ex);
        }
    }


    /**
     * {@inheritDoc}
     */
    @Override
    public List<SobiFragment> getPendingFragments(SortOrder sortByPubDate, LimitOffset limitOffset) {
        return sobiFragmentDao.getPendingSobiFragments(sortByPubDate, limitOffset);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int processFragments(List<SobiFragment> fragments, SobiProcessOptions options) {
        logger.debug((fragments.isEmpty()) ? "No more fragments to process"
                : "Iterating through {} fragments", fragments.size());
        for (SobiFragment fragment : fragments) {
            // Hand off processing to specific implementations based on fragment type.
            if (processorMap.containsKey(fragment.getType())) {
                processorMap.get(fragment.getType()).process(fragment);
            } else {
                logger.error("No processors have been registered to handle: " + fragment);
            }
            fragment.setProcessedCount(fragment.getProcessedCount() + 1);
            fragment.setProcessedDateTime(LocalDateTime.now());
        }
        // Perform any necessary post-processing/cleanup
        processorMap.values().forEach(p -> p.postProcess());
        // Set the fragments as processed and update
        fragments.forEach(f -> {
            f.setPendingProcessing(false);
            sobiFragmentDao.updateSobiFragment(f);
        });

        return fragments.size();
    }

    /**
     * {@inheritDoc}
     * <p>
     * Perform the operation in small batches so memory is not saturated.
     */
    @Override
    public int processPendingFragments(SobiProcessOptions options) {
        List<SobiFragment> fragments;
        int processCount = 0;
        do {
            ImmutableSet<SobiFragmentType> allowedTypes = options.getAllowedFragmentTypes();
            LimitOffset limOff = (env.isSobiBatchEnabled()) ? new LimitOffset(env.getSobiBatchSize()) : LimitOffset.ONE;
            fragments = sobiFragmentDao.getPendingSobiFragments(allowedTypes, SortOrder.ASC, limOff);
            processCount += processFragments(fragments, options);
        }
        while (!fragments.isEmpty() && env.isProcessingEnabled());
        return processCount;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void updatePendingProcessing(String fragmentId, boolean pendingProcessing)
            throws SobiFragmentNotFoundEx {
        try {
            SobiFragment fragment = sobiFragmentDao.getSobiFragment(fragmentId);
            fragment.setPendingProcessing(pendingProcessing);
            sobiFragmentDao.updateSobiFragment(fragment);
        } catch (DataAccessException ex) {
            throw new SobiFragmentNotFoundEx();
        }
    }

    /** --- Internal Methods --- */

    /**
     * Gets incoming {@link SourceFile}s from multiple sources
     * @return {@link List<SourceFile>}
     * @throws IOException
     */
    private List<SobiFile> getIncomingSourceFiles() throws IOException {
        List<SobiFile> incomingSourceFiles = new ArrayList<>();
        final int batchSize = env.getSobiBatchSize();

        for (SourceFileFsDao sourceFsDao : sourceFileFsDaos) {
            LimitOffset remainingLimit = new LimitOffset(batchSize - incomingSourceFiles.size());
            incomingSourceFiles.addAll(sourceFsDao.getIncomingSourceFiles(SortOrder.ASC, remainingLimit));
        }

        return incomingSourceFiles;
    }

    /**
     * Performs collate operations on a single source file
     * @param sourceFile
     * @throws IOException
     */
    private void collateSourceFile(SourceFile sourceFile) throws IOException {
        // Do some slightly different processing for SOBI and XML files
        DataProcessUnit unit = new DataProcessUnit(
                sourceFile.getSourceType().name(), sourceFile.getFileName(),
                LocalDateTime.now(), DataProcessAction.COLLATE);

        List<SobiFragment> fragments = createFragments(sourceFile);
        logger.info("Created {} fragments", fragments.size());

        // Record the source file in the backing store.
        sourceFileRefDao.updateSourceFile(sourceFile);
        // Save the extracted fragments. They will be marked as pending processing.
        for (SobiFragment fragment : fragments) {
            logger.info("Saving fragment {}", fragment.getFragmentId());
            fragment.setPendingProcessing(true);
            sobiFragmentDao.updateSobiFragment(fragment);
            unit.addMessage("Saved " + fragment.getFragmentId());
        }
        // Done with this source file so let's archive it.
        final SourceFileFsDao relevantFsDao = sourceFileDaoMap.get(sourceFile.getSourceType());
        relevantFsDao.archiveSourceFile(sourceFile);
        sourceFileRefDao.updateSourceFile(sourceFile);
        unit.setEndDateTime(LocalDateTime.now());
        eventBus.post(new DataProcessUnitEvent(unit));
    }

    /**
     * Extracts a list of SobiFragments from the given SobiFile.
     */
    private List<SobiFragment> createFragments(SourceFile sourceFile) throws IOException {
        List<SobiFragment> sobiFragments = new ArrayList<>();

        // Else continue with splitting the SOBI file into fragments
        StringBuilder billBuffer = new StringBuilder();

        boolean isPatch = false;
        StringBuilder patchMessage = new StringBuilder();

        // Incrementing sequenceNo maintains the order in which the sobi fragments were
        // found in the source sobiFile. However the sequence number for the bill fragment
        // is always set to 0 to ensure that they are always processed first.
        int sequenceNo = 1;

        // Replace the null characters with spaces and split by newline.
        List<String> lines = Arrays.asList(sourceFile.getText().replace('\0', ' ').split("\\r?\\n"));
        Iterator<String> lineIterator = lines.iterator();
        while (lineIterator.hasNext()) {
            String line = lineIterator.next();
            // Check for a patch tag indicating a manual fix
            if (patchTagPattern.matcher(line).matches()) {
                isPatch = true;
                extractPatchMessage(lineIterator, patchMessage);
            }
            SobiFragmentType fragmentType = getFragmentTypeFromLine(line);
            if (fragmentType != null) {
                // Bill fragments are in the sobi format and appended into a single buffer
                if (fragmentType.equals(SobiFragmentType.BILL)) {
                    // Memos need to be converted to latin1 encoding
                    if (line.charAt(11) == SobiLineType.SPONSOR_MEMO.getTypeCode()) {
                        line = new String(line.getBytes(sourceFile.getEncoding()), "latin1");
                    }
                    line = line.replace((char) 193, '°');
                    billBuffer.append(line).append("\n");
                }
                // Other fragment types are in XML format. The iterator moves past the closing xml
                // tag and the xml text is stored in the fragment.
                else {
                    String xmlText = extractXmlText(fragmentType, line, lineIterator);
                    SobiFragment fragment = new SobiFragment(sourceFile, fragmentType, xmlText, sequenceNo++);
                    sobiFragments.add(fragment);
                }
            }
        }
        // Convert the billBuffer into a single bill fragment (if applicable) with sequence no set to 0.
        if (billBuffer.length() > 0) {
            SobiFragment billFragment = new SobiFragment(sourceFile, SobiFragmentType.BILL, billBuffer.toString(), 0);
            sobiFragments.add(billFragment);
        }
        // Set manual fix flag and add notes if this file was a patch
        if (isPatch) {
            String notes = patchMessage.toString();
            sobiFragments.forEach(fragment -> {
                fragment.setManualFix(true);
                fragment.setManualFixNotes(notes);
            });
        }
        return sobiFragments;
    }

    /**
     * Check the given SOBI line to determine if it matches the start of a SOBI Fragment type.
     *
     * @param line String
     * @return SobiFragmentType or null if no match
     */
    private SobiFragmentType getFragmentTypeFromLine(String line) {
        for (SobiFragmentType fragmentType : SobiFragmentType.values()) {
            if (line.matches(fragmentType.getStartPattern())) {
                return fragmentType;
            }
        }
        return null;
    }

    /**
     * Gets a patch sobi message from within a set of patch tags, appending it to the given string builder
     *
     * @param lineIterator Iterator<String>
     * @param patchMessage StringBuilder
     */
    private void extractPatchMessage(Iterator<String> lineIterator, StringBuilder patchMessage) {
        while (lineIterator.hasNext()) {
            String line = lineIterator.next();
            if (patchTagPattern.matcher(line).matches()) {
                return;
            }
            if (patchMessage.length() > 0) {
                patchMessage.append("\n");
            }
            patchMessage.append(line.trim());
        }
    }

    /**
     * Extracts a well formed XML document from the lines and writes it to the given
     * file. This depends strongly on escape sequences being on their own line; otherwise
     * we'll get malformed XML docs.
     *
     * @param fragmentType SobiFragmentType
     * @param line         String - The starting line of the document
     * @param iterator     Iterator<String> - Current iterator from the sobi file's text body
     * @return String - The resulting XML string.
     * @throws java.io.IOException
     */
    private String extractXmlText(SobiFragmentType fragmentType, String line, Iterator<String> iterator) throws IOException {
        String endPattern = fragmentType.getEndPattern();
        StringBuffer xmlBuffer = new StringBuffer(
                "<?xml version='1.0' encoding='UTF-8'?>&newl;" + line + "&newl;"
        );
        while (iterator.hasNext()) {
            String next = iterator.next();
            xmlBuffer.append(next.replaceAll("\\xb9", "&sect;")).append("&newl;");
            if (next.matches(endPattern)) {
                break;
            }
        }
        String xmlString = xmlBuffer.toString();

        // TODO: Figure out this magic.
        xmlBuffer = new StringBuffer();
        Matcher m = Pattern.compile("<\\!\\[CDATA\\[(.*?)\\]\\]>").matcher(xmlString);
        while (m.find()) {
            String tmp1 = Matcher.quoteReplacement(m.group(0).replaceAll("&newl;", " ").replaceAll("\\\\n", "\n"));
            StringBuffer sb = new StringBuffer(tmp1.replaceFirst(" ",""));
            StringBuffer sb1 = new StringBuffer(sb.reverse().toString().replaceFirst(" ",""));
            m.appendReplacement(xmlBuffer, sb1.reverse().toString());
        }
        m.appendTail(xmlBuffer);

        // TODO: Figure out this magic as well.
        xmlString = xmlBuffer.toString().replaceAll("&newl;", "\n").replaceAll("(?!\n)\\p{Cntrl}", "").replaceAll("(?!\\.{2})[ ]{2,}", " ");
        return xmlString;
    }
}
