package com.heima.article.service;

import com.heima.model.article.pojos.ApArticle;
import org.springframework.stereotype.Service;



public interface ApArticleFreemarkerService {

    /*
    upload the static article file to minIO
     */

    public void buildArticleToMinIO(ApArticle apArticle, String content);
}
