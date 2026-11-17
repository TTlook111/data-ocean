package com.dataocean.module.iam.entity.query;
import com.dataocean.common.PageQuery;
import com.dataocean.common.TextSearch;
import java.util.List;
public class DepartmentOptionQuery {
    private String purpose = null;
    public String getPurpose() { return purpose; }
    public void setPurpose(String value) { purpose = value; }
    private String q = null;
    public String getQ() { return q; }
    public void setQ(String value) { q = value; }
    public List<String> getQTerms() { return TextSearch.terms(q); }
    private java.util.List<String> selectedIds = java.util.List.of();
    public java.util.List<String> getSelectedIds() { return selectedIds; }
    public void setSelectedIds(java.util.List<String> value) { selectedIds = value; }
}
