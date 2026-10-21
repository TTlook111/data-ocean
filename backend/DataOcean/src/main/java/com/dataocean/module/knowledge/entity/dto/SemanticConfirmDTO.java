package com.dataocean.module.knowledge.entity.dto;
import lombok.Data;
import java.util.List;
@Data
public class SemanticConfirmDTO {
    private Integer version;
    private boolean confirmed;
    private List<SelectionDTO> selections;
    @Data
    public static class SelectionDTO { private Long id; private String text; }
}
