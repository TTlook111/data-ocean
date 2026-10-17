package com.dataocean.module.glossary.entity.vo;

import com.dataocean.module.glossary.entity.po.*;
import java.util.List;

public record TermWorkspaceVO(GlossaryTerm term, List<TermColumnVO> columns,
        String mappingError, boolean needsReview, List<GlossaryTermReview> reviews) {}
