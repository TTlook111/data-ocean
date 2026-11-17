package com.dataocean.module.iam.entity.query;
import com.dataocean.common.PageQuery;
import com.dataocean.common.TextSearch;
import java.util.List;
public class RoleOptionQuery {
    private String purpose = null;
    public String getPurpose() { return purpose; }
    public void setPurpose(String value) { purpose = value; }
    private String userId = null;
    public String getUserId() { return userId; }
    public void setUserId(String value) { userId = value; }
    private String q = null;
    public String getQ() { return q; }
    public void setQ(String value) { q = value; }
    public List<String> getQTerms() { return TextSearch.terms(q); }
}
