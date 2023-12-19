package com.heima.wemedia.service.impl;

import com.alibaba.fastjson.JSONArray;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.heima.apis.article.IArticleClient;
import com.heima.apis.article.fallback.IArticleClientFallback;
import com.heima.common.aliyun.GreenImageScan;
import com.heima.common.aliyun.GreenTextScan;
import com.heima.common.tess4j.Tess4jClient;
import com.heima.file.service.FileStorageService;
import com.heima.model.article.dtos.ArticleDto;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.wemedia.pojos.WmChannel;
import com.heima.model.wemedia.pojos.WmNews;
import com.heima.model.wemedia.pojos.WmSensitive;
import com.heima.model.wemedia.pojos.WmUser;
import com.heima.utils.common.SensitiveWordUtil;
import com.heima.wemedia.mapper.WmChannelMapper;
import com.heima.wemedia.mapper.WmNewsMapper;
import com.heima.wemedia.mapper.WmSensitiveMapper;
import com.heima.wemedia.mapper.WmUserMapper;
import com.heima.wemedia.service.WmNewsAutoScanService;
import com.sun.xml.internal.messaging.saaj.util.ByteInputStream;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import java.util.stream.Collectors;

import static com.alibaba.fastjson.JSON.parseArray;
import static com.hankcs.hanlp.corpus.tag.Nature.e;


@Service
@Transactional
@Slf4j
public class WmNewsAutoScanServiceImpl implements WmNewsAutoScanService {

    @Autowired
    private WmNewsMapper wmNewsMapper;

    @Autowired
    private IArticleClientFallback iArticleClient;
    @Autowired
    private WmChannelMapper wmChannelMapper;
    @Autowired
    private WmUserMapper wmUserMapper;


    @Override
    @Async
    public void autoScanWmNews(Integer id) throws InvocationTargetException, IllegalAccessException {

        //1 query the article
        WmNews wmNews = wmNewsMapper.selectById(id);
        if(wmNews == null) throw new RuntimeException("WmNewsAutoScanServiceImpl-article does not exist!!");
        if(wmNews.getStatus().equals(WmNews.Status.SUBMIT.getCode())){

            Map<String, Object> textAndImage =  handleTextAndImages(wmNews);

            Boolean isSensitiveScan = handleSenstiveScan((String) textAndImage.get("content"), wmNews);
            if(!isSensitiveScan) return;

            //2 scan the content of the article with aliyun api
            Boolean isTextScan = handleTextScan((String) textAndImage.get("content"), wmNews);
            if(!isTextScan) return;


            //3 scan the picture of the article with aliyun api
            Boolean isImageScan = handleImageScan((List<String>) textAndImage.get("image"), wmNews);
            if(!isImageScan) return;
        }

        //4 when the article successfully go through the examination, save the article
        ResponseResult responseResult = saveAppArticles(wmNews);
        if(responseResult.getCode().equals(200)){
            throw  new RuntimeException("WmNewsAutoScanServiceImpl-article scan, save data failed");
        }
        wmNews.setArticleId((Long) responseResult.getData());
        updateWmNews(wmNews, (short) 9,"article scan success");


    }

    @Autowired
    private WmSensitiveMapper wmSensitiveMapper;

    private Boolean handleSenstiveScan(String content, WmNews wmNews) {

        Boolean flag = true;

        //1 get all sensitive words
        List<WmSensitive> wmSensitives = wmSensitiveMapper.selectList(Wrappers.<WmSensitive>lambdaQuery().select(WmSensitive::getSensitives));
        List<String> sensitiveList = wmSensitives.stream().map(WmSensitive::getSensitives).collect(Collectors.toList());
        //2 init the sensitive DFS Map
        SensitiveWordUtil.initMap(sensitiveList);
        //3 serch for machting words
        Map<String, Integer> map = SensitiveWordUtil.matchWords(content);
        if(map.size()>0) {
            updateWmNews(wmNews, (short) 2,"There exists illegal content in the current article");
            flag = false;
        }
        return flag;

    }


    private ResponseResult saveAppArticles(WmNews wmNews) throws InvocationTargetException, IllegalAccessException {
        ArticleDto articleDto = new ArticleDto();
        BeanUtils.copyProperties(wmNews,articleDto);
        articleDto.setLayout(wmNews.getType());

        WmChannel wmChannel = wmChannelMapper.selectById(wmNews.getChannelId());
        if(wmChannel.getName() != null) articleDto.setChannelName(wmChannel.getName());

        articleDto.setAuthorId(wmNews.getUserId().longValue());
        WmUser user = wmUserMapper.selectById(wmNews.getUserId());
        if(user != null){
            articleDto.setAuthorName(user.getName());
        }
        if(wmNews.getArticleId()!= null){
            articleDto.setId(wmNews.getArticleId());
        }
        articleDto.setCreatedTime(new Date());

        ResponseResult responseResult = iArticleClient.saveArticle(articleDto);

        return responseResult;
    }

    @Autowired
    private FileStorageService fileStorageService;

    @Autowired
    private GreenImageScan greenImageScan;
    @Autowired
    private Tess4jClient tess4jClient;

    private Boolean handleImageScan(List<String> images, WmNews wmNews) {

        Boolean flag = true;

        if(images == null || images.size() == 0) return flag;

        images =images.stream().distinct().collect(Collectors.toList());
        List<byte[]> imageList = new ArrayList<>();

        try {
            for (String image : images) {
                byte[] bytes = fileStorageService.downLoadFile(image);

                ByteArrayInputStream in = new ByteArrayInputStream(bytes);
                BufferedImage read = null;
                read = ImageIO.read(in);
                String result = tess4jClient.doOCR(read);
                Boolean isSensitive = handleSenstiveScan(result, wmNews);
                if(!isSensitive){
                    return isSensitive;
                }

                imageList.add(bytes);

            }
            } catch (Exception e) {
                e.printStackTrace();
            }


        try {
            Map map = greenImageScan.imageScan(imageList);
            if(map.get("suggestion").equals("block")){
                flag = false;
                updateWmNews(wmNews, (short) 2, "There exist illegal content");
            }
            if(map.get("suggestion").equals("review")){
                flag = false;
                updateWmNews(wmNews, (short) 3, "There exist unclear content");
            }
        } catch (Exception e) {
            flag = false;
            throw new RuntimeException(e);
        }
        return flag;

    }

    @Autowired
    private GreenTextScan greenTextScan;

    private Boolean handleTextScan(String content, WmNews wmNews) {

        boolean flag = true;
        if((wmNews.getTitle() +"-" + content).length() == 0) return flag;

        try {
            Map map = greenTextScan.greeTextScan((wmNews.getTitle() +"-" + content));
            if(map.get("suggestion").equals("block")){
                flag = false;
                updateWmNews(wmNews, (short) 2, "There exist illegal content");
            }
            if(map.get("suggestion").equals("review")){
                flag = false;
                updateWmNews(wmNews, (short) 3, "There exist unclear content");
            }
        } catch (Exception e) {
            flag = false;
            throw new RuntimeException(e);
        }
        return flag;
    }

    private void updateWmNews(WmNews wmNews, short status, String reason) {
        wmNews.setStatus((short) status);
        wmNews.setReason(reason);
        wmNewsMapper.updateById(wmNews);
    }

    /*
    get the text and the images from the wmnews
    get the cover image
     */
    private Map<String, Object> handleTextAndImages(WmNews wmNews) {

        StringBuilder stringBuilder = new StringBuilder();
        List<String> images = new ArrayList<>();

        if(StringUtils.isNotBlank(wmNews.getContent())){
            List<Map> maps = parseArray(wmNews.getContent(), Map.class);
            for (Map map : maps) {
                if(map.get("type").equals("text")){
                    stringBuilder.append(map.get("value"));
                }
                if(map.get("type").equals("image")){
                    images.add((String) map.get("value"));
                }

            }
        }
        if(StringUtils.isNotBlank(wmNews.getImages())){

            String[] split = wmNews.getImages().split(",");
            images.addAll(Arrays.asList(split));
        }

        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put("content", stringBuilder.toString());
        resultMap.put("images", images);

        return resultMap;
    }
}
