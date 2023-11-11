package com.heima.wemedia.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.wemedia.dtos.WmMaterialDto;
import com.heima.model.wemedia.pojos.WmMaterial;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

public interface WmMaterialService extends IService<WmMaterial> {



    /*
    upload pictures
     */
    public ResponseResult uploadPicture(MultipartFile multipartFile) throws IOException;

    /*
    list all materials
     */
    public ResponseResult findList(WmMaterialDto dto);


}