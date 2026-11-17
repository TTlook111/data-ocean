package com.dataocean.module.audit.entity.query;
import com.dataocean.common.PageQuery;
import com.dataocean.common.TextSearch;
import java.util.List;
public class AuditQuery extends PageQuery {
    private String actor = null;
    public String getActor() { return actor; }
    public void setActor(String value) { actor = value; }
    public List<String> getActorTerms() { return TextSearch.terms(actor); }
    private String actionCode = null;
    public String getActionCode() { return actionCode; }
    public void setActionCode(String value) { actionCode = value; }
    private String target = null;
    public String getTarget() { return target; }
    public void setTarget(String value) { target = value; }
    public List<String> getTargetTerms() { return TextSearch.terms(target); }
    private String outcome = null;
    public String getOutcome() { return outcome; }
    public void setOutcome(String value) { outcome = value; }
    private String fromDate = null;
    public String getFromDate() { return fromDate; }
    public void setFromDate(String value) { fromDate = value; }
    private String toDate = null;
    public String getToDate() { return toDate; }
    public void setToDate(String value) { toDate = value; }
    private String timeZone = "Asia/Shanghai";
    public String getTimeZone() { return timeZone; }
    public void setTimeZone(String value) { timeZone = value; }
    @Override protected java.util.Map<String,String> sortColumns() { return java.util.Map.of("occurredAt", "a.occurred_at"); }
}
