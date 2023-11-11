package com.heima.article.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.heima.model.article.dtos.ArticleHomeDto;
import com.heima.model.article.pojos.ApArticle;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;
@Mapper
public interface ApArticleMapper extends BaseMapper<ApArticle> {

    /*
    type: 1 means load more, 2 means load newest
     */
    public List<ApArticle> loadArticleList(ArticleHomeDto dto, Short type);


}
