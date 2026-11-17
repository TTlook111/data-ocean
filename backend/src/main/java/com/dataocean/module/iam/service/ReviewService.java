package com.dataocean.module.iam.service;

import com.dataocean.module.iam.entity.vo.ChangeVO;
import com.dataocean.module.iam.entity.vo.ImpactCountVO;
import com.dataocean.module.iam.entity.vo.ImpactPreviewVO;
import java.util.List;

public interface ReviewService {
    ImpactPreviewVO preview(String action,String target,Integer version,Object proposal,List<ChangeVO> changes,List<ImpactCountVO> affected,List<String> warnings);
    void verify(String action,String target,Integer version,Object proposal,String token);
}
