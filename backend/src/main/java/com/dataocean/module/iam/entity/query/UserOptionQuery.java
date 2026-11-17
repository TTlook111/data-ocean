package com.dataocean.module.iam.entity.query;
import com.dataocean.common.PageQuery;
import com.dataocean.common.TextSearch;
import java.util.List;
public class UserOptionQuery {
    private String purpose = null;
    public String getPurpose() { return purpose; }
    public void setPurpose(String value) { purpose = value; }
    private String departmentId = null;
    public String getDepartmentId() { return departmentId; }
    public void setDepartmentId(String value) { departmentId = value; }
    private String q = null;
    public String getQ() { return q; }
    public void setQ(String value) { q = value; }
    public List<String> getQTerms() { return TextSearch.terms(q); }
}
