package gov.nysenate.openleg.model.daybreak;

import gov.nysenate.openleg.model.base.Version;
import gov.nysenate.openleg.model.bill.BaseBillId;
import gov.nysenate.openleg.model.bill.BillAction;
import gov.nysenate.openleg.model.spotcheck.SpotCheckRefType;
import gov.nysenate.openleg.model.spotcheck.SpotCheckReferenceId;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * A DaybreakBill serves as a model to store extracted bill content from the DaybreakFragments.
 */
public class DaybreakBill implements SpotCheckReferenceId
{
    /** Date of the report that created this instance. */
    protected LocalDate reportDate;

    /** Below are a subset of fields similarly found in {@link gov.nysenate.openleg.model.bill.Bill} */

    protected BaseBillId baseBillId;
    protected Version activeVersion;
    protected String title;
    protected String sponsor;
    protected List<String> cosponsors;
    protected List<String> multiSponsors;
    protected String lawCodeSummary;
    protected String lawSection;
    protected List<BillAction> actions;

    /** Mapping of amendments to their version id. */
    protected Map<Version, DaybreakBillAmendment> amendments;

    /** --- Constructors --- */

    public DaybreakBill() {}

    public DaybreakBill(DaybreakBillId daybreakBillId) {
        this.baseBillId = daybreakBillId.getBaseBillId();
        this.reportDate = daybreakBillId.getReportDate();
    }

    /** --- Implemented Methods --- */

    @Override
    public SpotCheckRefType getRefType() {
        return SpotCheckRefType.LBDC_DAYBREAK;
    }

    @Override
    public LocalDateTime getRefActiveDate() {
        return this.reportDate.atStartOfDay();
    }

    /** --- Functional Getters/Setters --- */

    public DaybreakBillId getDaybreakBillId(){
        return new DaybreakBillId(baseBillId, reportDate);
    }

    /** --- Basic Getters/Setters --- */

    public LocalDate getReportDate() {
        return reportDate;
    }

    public void setReportDate(LocalDate reportDate) {
        this.reportDate = reportDate;
    }

    public BaseBillId getBaseBillId() {
        return baseBillId;
    }

    public void setBaseBillId(BaseBillId baseBillId) {
        this.baseBillId = baseBillId;
    }

    public Version getActiveVersion() {
        return activeVersion;
    }

    public void setActiveVersion(Version activeVersion) {
        this.activeVersion = activeVersion;
    }

    public String getSponsor() {
        return sponsor;
    }

    public void setSponsor(String sponsor) {
        this.sponsor = sponsor;
    }

    public List<String> getCosponsors() {
        return cosponsors;
    }

    public void setCosponsors(List<String> cosponsors) {
        this.cosponsors = cosponsors;
    }

    public List<String> getMultiSponsors() {
        return multiSponsors;
    }

    public void setMultiSponsors(List<String> multiSponsors) {
        this.multiSponsors = multiSponsors;
    }

    public String getLawCodeSummary() {
        return lawCodeSummary;
    }

    public void setLawCodeSummary(String lawCodeSummary) {
        this.lawCodeSummary = lawCodeSummary;
    }

    public String getLawSection() {
        return lawSection;
    }

    public void setLawSection(String lawSection) {
        this.lawSection = lawSection;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public List<BillAction> getActions() {
        return actions;
    }

    public void setActions(List<BillAction> actions) {
        this.actions = actions;
    }

    public Map<Version, DaybreakBillAmendment> getAmendments() {
        return amendments;
    }

    public void setAmendments(Map<Version, DaybreakBillAmendment> amendments) {
        this.amendments = amendments;
    }
}