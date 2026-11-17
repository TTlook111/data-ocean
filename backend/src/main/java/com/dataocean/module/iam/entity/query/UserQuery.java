package com.dataocean.module.iam.entity.query;
import com.dataocean.common.PageQuery;
import com.dataocean.common.TextSearch;
import java.util.List;
public class UserQuery extends PersonQuery {
    private String departmentId = null;
    public String getDepartmentId() { return departmentId; }
    public void setDepartmentId(String value) { departmentId = value; }
    private boolean includeDescendants = true;
    public boolean getIncludeDescendants() { return includeDescendants; }
    public void setIncludeDescendants(boolean value) { includeDescendants = value; }
    @Override protected java.util.Map<String,String> sortColumns() { return java.util.Map.of("updatedAt", "u.updated_at", "username", "u.username", "displayName", "u.display_name"); }
}
