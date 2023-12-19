package com.heima.wemedia.service.impl;


import com.alibaba.fastjson.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.heima.common.constants.WemediaConstants;
import com.heima.common.constants.WmNewsMessageConstants;
import com.heima.common.exception.CustomException;
import com.heima.model.common.dtos.PageResponseResult;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.common.enums.AppHttpCodeEnum;
import com.heima.model.wemedia.dtos.WmNewsDto;
import com.heima.model.wemedia.dtos.WmNewsPageReqDto;
import com.heima.model.wemedia.pojos.WmMaterial;
import com.heima.model.wemedia.pojos.WmNews;
import com.heima.model.wemedia.pojos.WmNewsMaterial;
import com.heima.utils.thread.WmThreadLocalUtil;
import com.heima.wemedia.mapper.WmMaterialMapper;
import com.heima.wemedia.mapper.WmNewsMapper;
import com.heima.wemedia.mapper.WmNewsMaterialMapper;
import com.heima.wemedia.service.WmNewsAutoScanService;
import com.heima.wemedia.service.WmNewsService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.InvocationTargetException;
import java.util.*;

import java.util.stream.Collectors;


@Service
@Slf4j
@Transactional
public class WmNewsServiceImpl  extends ServiceImpl<WmNewsMapper, WmNews> implements WmNewsService {

    @Autowired
    private WmNewsMaterialMapper wmNewsMaterialMapper;
    @Autowired
    private WmMaterialMapper wmMaterialMapper;

    @Autowired
    private KafkaTemplate kafkaTemplate;


    @Override
    public ResponseResult findList(WmNewsPageReqDto dto) {

        //1 examine parameters
        dto.checkParam();
        //2 paged conditional query
        IPage page = new Page(dto.getPage(),dto.getSize());
        LambdaQueryWrapper<WmNews> lambdaQueryWrapper = new LambdaQueryWrapper();

        //exact conditional query based on status
        if(dto.getStatus() != null) lambdaQueryWrapper.eq(WmNews::getStatus, dto.getStatus());
        //exact conditional query based on channel
        if(dto.getChannelId() != null) lambdaQueryWrapper.eq(WmNews::getChannelId, dto.getChannelId());
        //exact conditional query based on time
        if(dto.getBeginPubDate() != null && dto.getEndPubDate() != null){
            lambdaQueryWrapper.between(WmNews::getPublishTime, dto.getBeginPubDate(), dto.getEndPubDate());
        }
        //mohu query based on key word
        if(StringUtils.isNotBlank(dto.getKeyword())) lambdaQueryWrapper.like(WmNews::getTitle, dto.getKeyword());
        //query the articles of current user
        lambdaQueryWrapper.eq(WmNews::getUserId, WmThreadLocalUtil.getUser().getId());
        //query all results based on created time desc
        lambdaQueryWrapper.orderByDesc(WmNews::getCreatedTime);

        page = page(page, lambdaQueryWrapper);

        //3 return result
        ResponseResult responseResult = new PageResponseResult(dto.getPage(),dto.getSize(), (int) page.getTotal());
        responseResult.setData(page.getRecords());
        return responseResult;
    }

    @Autowired
    private WmNewsAutoScanService wmNewsAutoScanService;

    @Override
    public ResponseResult submitNews(WmNewsDto dto) throws InvocationTargetException, IllegalAccessException {
        //0 examine parameter
        if(dto == null || dto.getContent() == null) return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID);
        //1 add a new article or update the existing article
        WmNews wmNews = new WmNews();
        BeanUtils.copyProperties(dto,wmNews);

        if(dto.getImages() != null && dto.getImages().size()>0){
            String images = StringUtils.join(dto.getImages(),",");
            wmNews.setImages(images);
        }

        if(dto.getType().equals(WemediaConstants.WM_NEWS_TYPE_AUTO)) wmNews.setType(null);

        //2 examine if the submission is a draft
        saveOrUpdateWmNews(wmNews);
        if(dto.getStatus().equals(WmNews.Status.NORMAL.getCode())){
            return ResponseResult.okResult(AppHttpCodeEnum.SUCCESS);
        }

        //3 if yes, close current method
        List<String> materials = extractUrlInfo(dto.getContent());
        saveRelativeInfoForContent(materials, wmNews.getId());

        //4 if no, save the relationship between article and the materials
        saveRelativeInfoForCover(dto, wmNews, materials);

        wmNewsAutoScanService.autoScanWmNews(wmNews.getId());

        return ResponseResult.okResult(AppHttpCodeEnum.SUCCESS);
    }

    private void saveRelativeInfoForCover(WmNewsDto dto, WmNews wmNews, List<String> materials) {
        List<String> images = dto.getImages();

        if(dto.getType().equals(WemediaConstants.WM_NEWS_TYPE_AUTO)){

            if(materials.size()>=3){
                wmNews.setType(WemediaConstants.WM_NEWS_MANY_IMAGE);
                images = materials.stream().limit(3).collect(Collectors.toList());
            }else if(materials.size()>=1 && materials.size()<3){
                wmNews.setType(WemediaConstants.WM_NEWS_SINGLE_IMAGE);
                images = materials.stream().limit(1).collect(Collectors.toList());
            }else{
                wmNews.setType(WemediaConstants.WM_NEWS_NONE_IMAGE);
            }

            if (images != null && images.size() > 0) {
                wmNews.setImages(StringUtils.join(images,","));
            }
            updateById(wmNews);
        }
        if(images!= null&&images.size()>0){
            saveRelativeInfo(images, wmNews.getId(),WemediaConstants.WM_COVER_REFERENCE);
        }
    }

    private void saveRelativeInfoForContent(List<String> materials, Integer newsId) {
        saveRelativeInfo(materials, newsId, WemediaConstants.WM_CONTENT_REFERENCE);
    }

    private void saveRelativeInfo(List<String> materials, Integer newsId, Short type) {

        if(materials.size() != 0 && !materials.isEmpty()){

            List<WmMaterial> dbMaterials = wmMaterialMapper.selectList(Wrappers.<WmMaterial>lambdaQuery().in(WmMaterial::getUrl, materials));
            if(dbMaterials == null || dbMaterials.size() == 0){
                throw new CustomException(AppHttpCodeEnum.MATERIALS_REFERENCE_FAIL);
            }
            if(materials.size()!= dbMaterials.size()){
                throw new CustomException(AppHttpCodeEnum.MATERIALS_REFERENCE_FAIL);
            }
            List<Integer> idList = dbMaterials.stream().map(WmMaterial::getId).collect(Collectors.toList());
            wmNewsMaterialMapper.saveRelations(idList, newsId,type);
        }

    }

    private List<String> extractUrlInfo(String content) {
        List<Map> maps = JSON.parseArray(content, Map.class);
        List<String> materials = new ArrayList<>();
        for (Map map : maps) {
            if(map.get("type").equals("image")){
                String image = (String) map.get("value");
                materials.add(image);
            }
        }
        return materials;
    }

    private void saveOrUpdateWmNews(WmNews wmNews) {
        //complete parameters
        wmNews.setUserId(WmThreadLocalUtil.getUser().getId());
        wmNews.setCreatedTime(new Date());
        wmNews.setPublishTime(new Date());
        wmNews.setEnable((short) 1);

        if(wmNews.getId() == null) {
            save(wmNews);
        }else{
            wmNewsMaterialMapper.delete(Wrappers.<WmNewsMaterial>lambdaQuery().eq(WmNewsMaterial::getNewsId, wmNews.getId()));
            updateById(wmNews);
        }
    }

    @Override
    public ResponseResult downOrUp(WmNewsDto dto) {
        //1 examine parameters
        if(dto.getId() == null){
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID);
        }
        //2 query the article
        WmNews news = getById(dto.getId());
        if(news == null){
            return ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST, "the article does not exist");
        }
        //3 check if the article is published
        if(!news.getStatus().equals(WmNews.Status.PUBLISHED)){
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "the article is not publised, cannot down or up");
        }

        //4 adjust the enable data field of the article
        if(news.getEnable()!= null&& news.getEnable() >-1 && news.getEnable()<2){
            update(Wrappers.<WmNews>lambdaUpdate().set(WmNews::getEnable,dto.getEnable()).eq(WmNews::getId, news.getId()));
            if(news.getArticleId()!= null){
                Map<String, Object> map = new HashMap<>();
                map.put("articleId", news.getArticleId());
                map.put("enabled", news.getEnable());
                kafkaTemplate.send(WmNewsMessageConstants.WM_NEWS_UP_OR_DOWN_TOPIC,JSON.toJSONString(map));
            }



        }
        return ResponseResult.okResult(AppHttpCodeEnum.SUCCESS);
    }
}
