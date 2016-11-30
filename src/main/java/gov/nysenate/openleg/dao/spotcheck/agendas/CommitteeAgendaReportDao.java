package gov.nysenate.openleg.dao.spotcheck.agendas;

import com.google.common.collect.ImmutableMap;
import gov.nysenate.openleg.dao.spotcheck.SpotCheckContentIdMapper;
import gov.nysenate.openleg.model.agenda.AgendaId;
import gov.nysenate.openleg.model.agenda.CommitteeAgendaAddendumId;
import gov.nysenate.openleg.model.base.Version;
import gov.nysenate.openleg.model.entity.Chamber;
import gov.nysenate.openleg.model.entity.CommitteeId;
import gov.nysenate.openleg.model.spotcheck.SpotCheckContentType;
import gov.nysenate.openleg.model.spotcheck.SpotCheckDataSource;
import gov.nysenate.openleg.model.spotcheck.SpotCheckRefType;
import org.springframework.stereotype.Repository;

import java.util.Map;

@Repository
public class CommitteeAgendaReportDao implements SpotCheckContentIdMapper<CommitteeAgendaAddendumId> {


    @Override
    public SpotCheckContentType getContentType() {
        return SpotCheckContentType.AGENDA;
    }

    @Override
    public SpotCheckDataSource getDataSource() {
        return SpotCheckDataSource.NYSENATE_DOT_GOV;
    }

    @Override
    public CommitteeAgendaAddendumId getKeyFromMap(Map<String, String> keyMap) {
        return new CommitteeAgendaAddendumId(
                new AgendaId(
                        Long.parseLong(keyMap.get("agenda_no")),
                        Integer.parseInt(keyMap.get("year"))
                ),
                new CommitteeId(
                        Chamber.getValue(keyMap.get("chamber")),
                        keyMap.get("committee_name")
                ),
                Version.of(keyMap.get("addendum"))
        );
    }

    @Override
    public Map<String, String> getMapFromKey(CommitteeAgendaAddendumId addendumId) {
        return ImmutableMap.<String, String>builder()
                .put("agenda_no", addendumId.getAgendaId().getNumber().toString())
                .put("year", Integer.toString(addendumId.getAgendaId().getYear()))
                .put("chamber", addendumId.getCommitteeId().getChamber().asSqlEnum())
                .put("committee_name", addendumId.getCommitteeId().getName())
                .put("addendum", addendumId.getAddendum().name())
                .build();
    }
}
