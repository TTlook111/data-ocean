package com.dataocean.module.iam.entity.query;

import com.dataocean.common.PageQuery;
import com.dataocean.common.TextSearch;
import jakarta.validation.constraints.Pattern;
import java.util.List;

public class PersonQuery extends PageQuery {
    private String displayName;
    private String username;
    @Pattern(regexp="PENDING_FIRST_LOGIN|PASSWORD_CHANGE_REQUIRED|ENABLED|DISABLED|ARCHIVED")
    private String displayStatus;
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String value) { displayName=value; }
    public String getUsername() { return username; }
    public void setUsername(String value) { username=value; }
    public String getDisplayStatus() { return displayStatus; }
    public void setDisplayStatus(String value) { displayStatus=value; }
    public List<String> getDisplayNameTerms() { return TextSearch.terms(displayName); }
    public List<String> getUsernameTerms() { return TextSearch.terms(username); }
}
