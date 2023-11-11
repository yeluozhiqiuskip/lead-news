package com.heima.model.wemedia.dtos;

import com.heima.model.common.dtos.PageRequestDto;
import lombok.Data;


@Data
public class WmMaterialDto extends PageRequestDto {

    private short isCollection;

}
