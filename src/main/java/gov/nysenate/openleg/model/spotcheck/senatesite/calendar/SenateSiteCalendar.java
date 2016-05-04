package gov.nysenate.openleg.model.spotcheck.senatesite.calendar;

import gov.nysenate.openleg.model.base.Version;
import gov.nysenate.openleg.model.bill.BillId;
import gov.nysenate.openleg.model.calendar.CalendarId;
import gov.nysenate.openleg.model.calendar.CalendarType;
import gov.nysenate.openleg.model.calendar.spotcheck.CalendarEntryListId;
import gov.nysenate.openleg.model.spotcheck.SpotCheckRefType;
import gov.nysenate.openleg.model.spotcheck.SpotCheckReferenceId;

import java.time.LocalDateTime;
import java.util.List;


/**
 * Created by PKS on 3/3/16.
 */
public class SenateSiteCalendar {
    protected LocalDateTime referenceDateTime;
    protected CalendarType calendarType;
    protected List<Integer> billCalNumbers;
    protected CalendarId calendarId;
    protected List<BillId> bill;
    protected Integer sequenceNo;
    protected Version version;


    public SenateSiteCalendar(LocalDateTime referenceDateTime) {
        this.referenceDateTime = referenceDateTime;
    }

    /** --- Functional Getters --- */

    public CalendarId getCalendarId(){
        return calendarId;
    }

    public CalendarEntryListId getCalendarEntryListId() {
        return new CalendarEntryListId(calendarId, calendarType, version, sequenceNo);
    }

    public SpotCheckReferenceId getReferenceId() {
        return new SpotCheckReferenceId(SpotCheckRefType.SENATE_SITE_CALENDAR, referenceDateTime);
    }

    /** --- Getters / Setters --- */

    public void setCalendarId(CalendarId calendarId){
        this.calendarId = calendarId;
    }

    public CalendarType getCalendarType(){
        return calendarType;
    }

    public void setCalendarType(CalendarType calendarType){
        this.calendarType = calendarType;
    }

    public List<Integer> getBillCalNumbers(){
        return billCalNumbers;
    }

    public void setBillCalNumbers(List<Integer> billCalNumbers){
        this.billCalNumbers = billCalNumbers;
    }

    public List<BillId> getBill(){
        return bill;
    }

    public void setBill(List<BillId> billIds){
        this.bill = billIds;
    }
    public Integer getSequenceNo(){
        return sequenceNo;
    }
    public void setSequenceNo(Integer sequenceNo){
        this.sequenceNo = sequenceNo;
    }

    public Version getVersion(){
        if (calendarType == CalendarType.FLOOR_CALENDAR){
            return Version.DEFAULT;
        }
        return version;
    }

    public void setVersion(Version version){
        this.version = version;
    }
}
