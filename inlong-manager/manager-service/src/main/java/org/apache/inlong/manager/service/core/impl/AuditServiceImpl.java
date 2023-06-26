/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.inlong.manager.service.core.impl;

import org.apache.inlong.manager.common.consts.SourceType;
import org.apache.inlong.manager.common.enums.AuditQuerySource;
import org.apache.inlong.manager.common.enums.ClusterType;
import org.apache.inlong.manager.common.enums.ErrorCodeEnum;
import org.apache.inlong.manager.common.enums.TimeStaticsDim;
import org.apache.inlong.manager.common.util.Preconditions;
import org.apache.inlong.manager.dao.entity.AuditBaseEntity;
import org.apache.inlong.manager.dao.entity.StreamSinkEntity;
import org.apache.inlong.manager.dao.entity.StreamSourceEntity;
import org.apache.inlong.manager.dao.mapper.AuditBaseEntityMapper;
import org.apache.inlong.manager.dao.mapper.AuditEntityMapper;
import org.apache.inlong.manager.dao.mapper.StreamSinkEntityMapper;
import org.apache.inlong.manager.dao.mapper.StreamSourceEntityMapper;
import org.apache.inlong.manager.pojo.audit.AuditInfo;
import org.apache.inlong.manager.pojo.audit.AuditRequest;
import org.apache.inlong.manager.pojo.audit.AuditVO;
import org.apache.inlong.manager.pojo.user.LoginUserUtils;
import org.apache.inlong.manager.pojo.user.UserRoleCode;
import org.apache.inlong.manager.service.core.AuditService;
import org.apache.inlong.manager.service.resource.sink.ck.ClickHouseConfig;
import org.apache.inlong.manager.service.resource.sink.es.ElasticsearchApi;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.ibatis.jdbc.SQL;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.search.aggregations.Aggregation;
import org.elasticsearch.search.aggregations.AggregationBuilders;
import org.elasticsearch.search.aggregations.bucket.terms.ParsedTerms;
import org.elasticsearch.search.aggregations.bucket.terms.TermsAggregationBuilder;
import org.elasticsearch.search.aggregations.metrics.sum.ParsedSum;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.search.sort.SortOrder;
import org.joda.time.DateTime;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static org.elasticsearch.index.query.QueryBuilders.termQuery;

/**
 * Audit service layer implementation
 */
@Lazy
@Service
public class AuditServiceImpl implements AuditService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AuditServiceImpl.class);
    private static final String SECOND_FORMAT = "yyyy-MM-dd HH:mm:ss";
    private static final String HOUR_FORMAT = "yyyy-MM-dd HH";
    private static final String DAY_FORMAT = "yyyy-MM-dd";

    // key: type of audit base item, value: entity of audit base item
    private final Map<String, AuditBaseEntity> auditSentItemMap = new ConcurrentHashMap<>();

    private final Map<String, AuditBaseEntity> auditReceivedItemMap = new ConcurrentHashMap<>();

    // defaults to return all audit ids, can be overwritten in properties file
    // see audit id definitions: https://inlong.apache.org/docs/modules/audit/overview#audit-id
    @Value("#{'${audit.admin.ids:3,4,5,6}'.split(',')}")
    private List<String> auditIdListForAdmin;
    @Value("#{'${audit.user.ids:3,4,5,6}'.split(',')}")
    private List<String> auditIdListForUser;

    @Value("${audit.query.source}")
    private String auditQuerySource = AuditQuerySource.MYSQL.name();

    @Autowired
    private AuditBaseEntityMapper auditBaseMapper;
    @Autowired
    private AuditEntityMapper auditEntityMapper;
    @Autowired
    private ElasticsearchApi elasticsearchApi;
    @Autowired
    private StreamSinkEntityMapper sinkEntityMapper;
    @Autowired
    private StreamSourceEntityMapper sourceEntityMapper;

    @PostConstruct
    public void initialize() {
        LOGGER.info("init audit base item cache map for {}", AuditServiceImpl.class.getSimpleName());
        try {
            refreshBaseItemCache();
        } catch (Throwable t) {
            LOGGER.error("initialize audit base item cache error", t);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean refreshBaseItemCache() {
        LOGGER.debug("start to reload audit base item info");
        try {
            List<AuditBaseEntity> auditBaseEntities = auditBaseMapper.selectAll();
            for (AuditBaseEntity auditBaseEntity : auditBaseEntities) {
                String type = auditBaseEntity.getType();
                if (auditBaseEntity.getIsSent() == 1) {
                    auditSentItemMap.put(type, auditBaseEntity);
                } else {
                    auditReceivedItemMap.put(type, auditBaseEntity);
                }
            }
        } catch (Throwable t) {
            LOGGER.error("failed to reload audit base item info", t);
            return false;
        }

        LOGGER.debug("success to reload audit base item info");
        return true;
    }

    @Override
    public String getAuditId(String type, boolean isSent) {
        if (StringUtils.isBlank(type)) {
            return null;
        }
        AuditBaseEntity auditBaseEntity = isSent ? auditSentItemMap.get(type) : auditReceivedItemMap.get(type);
        if (auditBaseEntity != null) {
            return auditBaseEntity.getAuditId();
        }
        auditBaseEntity = auditBaseMapper.selectByTypeAndIsSent(type, isSent ? 1 : 0);
        Preconditions.expectNotNull(auditBaseEntity, ErrorCodeEnum.AUDIT_ID_TYPE_NOT_SUPPORTED,
                String.format(ErrorCodeEnum.AUDIT_ID_TYPE_NOT_SUPPORTED.getMessage(), type));
        if (isSent) {
            auditSentItemMap.put(type, auditBaseEntity);
        } else {
            auditReceivedItemMap.put(type, auditBaseEntity);
        }
        return auditBaseEntity.getAuditId();
    }

    @Override
    public List<AuditVO> listByCondition(AuditRequest request) throws Exception {
        LOGGER.info("begin query audit list request={}", request);
        Preconditions.expectNotNull(request, "request is null");

        String groupId = request.getInlongGroupId();
        String streamId = request.getInlongStreamId();

        // for now, we use the first sink type only.
        // this is temporary behavior before multiple sinks in one stream is fully supported.
        String sinkNodeType = null;
        Integer sinkId = request.getSinkId();
        if (sinkId == null) {
            List<StreamSinkEntity> sinkEntityList = sinkEntityMapper.selectByRelatedId(groupId, streamId);
            sinkNodeType = sinkEntityList.get(0).getSinkType();
        } else {
            StreamSinkEntity sinkEntity = sinkEntityMapper.selectByPrimaryKey(sinkId);
            sinkNodeType = sinkEntity.getSinkType();
        }

        // properly overwrite audit ids by role and stream config
        request.setAuditIds(getAuditIds(groupId, streamId, sinkNodeType));

        List<AuditVO> result = new ArrayList<>();
        AuditQuerySource querySource = AuditQuerySource.valueOf(auditQuerySource);
        for (String auditId : request.getAuditIds()) {
            if (AuditQuerySource.MYSQL == querySource) {
                String format = "%Y-%m-%d %H:%i:00";
                // Support min agg at now
                DateTimeFormatter forPattern = DateTimeFormat.forPattern("yyyy-MM-dd");
                DateTime dtDate = forPattern.parseDateTime(request.getDt());
                String eDate = dtDate.plusDays(1).toString(forPattern);
                List<Map<String, Object>> sumList = auditEntityMapper.sumByLogTs(
                        groupId, streamId, auditId, request.getDt(), eDate, format);
                List<AuditInfo> auditSet = sumList.stream().map(s -> {
                    AuditInfo vo = new AuditInfo();
                    vo.setLogTs((String) s.get("logTs"));
                    vo.setCount(((BigDecimal) s.get("total")).longValue());
                    return vo;
                }).collect(Collectors.toList());
                result.add(new AuditVO(auditId, auditSet,
                        auditId.equals(getAuditId(sinkNodeType, true)) ? sinkNodeType : null));
            } else if (AuditQuerySource.ELASTICSEARCH == querySource) {
                String index = String.format("%s_%s", request.getDt().replaceAll("-", ""), auditId);
                if (!elasticsearchApi.indexExists(index)) {
                    LOGGER.warn("elasticsearch index={} not exists", index);
                    continue;
                }
                SearchResponse response = elasticsearchApi.search(toAuditSearchRequest(index, groupId, streamId));
                final List<Aggregation> aggregations = response.getAggregations().asList();
                if (CollectionUtils.isNotEmpty(aggregations)) {
                    ParsedTerms terms = (ParsedTerms) aggregations.get(0);
                    if (CollectionUtils.isNotEmpty(terms.getBuckets())) {
                        List<AuditInfo> auditSet = terms.getBuckets().stream().map(bucket -> {
                            AuditInfo vo = new AuditInfo();
                            vo.setLogTs(bucket.getKeyAsString());
                            vo.setCount((long) ((ParsedSum) bucket.getAggregations().asList().get(0)).getValue());
                            return vo;
                        }).collect(Collectors.toList());
                        result.add(new AuditVO(auditId, auditSet,
                                auditId.equals(getAuditId(sinkNodeType, true)) ? sinkNodeType : null));
                    }
                }
            } else if (AuditQuerySource.CLICKHOUSE == querySource) {
                try (Connection connection = ClickHouseConfig.getCkConnection();
                        PreparedStatement statement =
                                getAuditCkStatement(connection, groupId, streamId, auditId, request.getDt());
                        ResultSet resultSet = statement.executeQuery()) {
                    List<AuditInfo> auditSet = new ArrayList<>();
                    while (resultSet.next()) {
                        AuditInfo vo = new AuditInfo();
                        vo.setLogTs(resultSet.getString("log_ts"));
                        vo.setCount(resultSet.getLong("total"));
                        auditSet.add(vo);
                    }
                    result.add(new AuditVO(auditId, auditSet,
                            auditId.equals(getAuditId(sinkNodeType, true)) ? sinkNodeType : null));
                }
            }
        }
        LOGGER.info("success to query audit list for request={}", request);
        return aggregateByTimeDim(result, request.getTimeStaticsDim());
    }

    private List<String> getAuditIds(String groupId, String streamId, String sinkNodeType) {
        Set<String> auditSet = LoginUserUtils.getLoginUser().getRoles().contains(UserRoleCode.TENANT_ADMIN)
                ? new HashSet<>(auditIdListForAdmin)
                : new HashSet<>(auditIdListForUser);

        // if no sink is configured, return data-proxy output instead of sort
        if (sinkNodeType == null) {
            auditSet.add(getAuditId(ClusterType.DATAPROXY, true));
        } else {
            auditSet.add(getAuditId(sinkNodeType, false));
        }

        // auto push source has no agent, return data-proxy audit data instead of agent
        List<StreamSourceEntity> sourceList = sourceEntityMapper.selectByRelatedId(groupId, streamId, null);
        if (CollectionUtils.isEmpty(sourceList)
                || sourceList.stream().allMatch(s -> SourceType.AUTO_PUSH.equals(s.getSourceType()))) {
            // need data_proxy received type when agent has received type
            boolean dpReceivedNeeded = auditSet.contains(getAuditId(ClusterType.AGENT, false));
            if (dpReceivedNeeded) {
                auditSet.add(getAuditId(ClusterType.DATAPROXY, false));
            }
        }

        return new ArrayList<>(auditSet);
    }

    /**
     * Convert to elasticsearch search request
     *
     * @param index The index of elasticsearch
     * @param groupId The groupId of inlong
     * @param streamId The streamId of inlong
     * @return The search request of elasticsearch
     */
    private SearchRequest toAuditSearchRequest(String index, String groupId, String streamId) {
        TermsAggregationBuilder builder = AggregationBuilders.terms("log_ts").field("log_ts")
                .size(Integer.MAX_VALUE).subAggregation(AggregationBuilders.sum("count").field("count"));
        BoolQueryBuilder filterBuilder = new BoolQueryBuilder();
        filterBuilder.must(termQuery("inlong_group_id", groupId));
        filterBuilder.must(termQuery("inlong_stream_id", streamId));
        SearchSourceBuilder sourceBuilder = new SearchSourceBuilder();
        sourceBuilder.aggregation(builder);
        sourceBuilder.query(filterBuilder);
        sourceBuilder.from(0);
        sourceBuilder.size(0);
        sourceBuilder.sort("log_ts", SortOrder.ASC);
        return new SearchRequest(new String[]{index}, sourceBuilder);
    }

    /**
     * Get clickhouse Statement
     *
     * @param connection The ClickHouse connection
     * @param groupId The groupId of inlong
     * @param streamId The streamId of inlong
     * @param auditId The auditId of request
     * @param dt The datetime of request
     * @return The clickhouse Statement
     */
    private PreparedStatement getAuditCkStatement(Connection connection, String groupId, String streamId,
            String auditId, String dt) throws SQLException {
        DateTimeFormatter formatter = DateTimeFormat.forPattern(DAY_FORMAT);
        DateTime date = formatter.parseDateTime(dt);
        String startDate = date.toString(SECOND_FORMAT);
        String endDate = date.plusDays(1).toString(SECOND_FORMAT);

        String sql = new SQL()
                .SELECT("log_ts", "sum(count) as total")
                .FROM("audit_data")
                .WHERE("inlong_group_id = ?")
                .WHERE("inlong_stream_id = ?")
                .WHERE("audit_id = ?")
                .WHERE("log_ts >= ?")
                .WHERE("log_ts < ?")
                .GROUP_BY("log_ts")
                .ORDER_BY("log_ts")
                .toString();

        PreparedStatement statement = connection.prepareStatement(sql);
        statement.setString(1, groupId);
        statement.setString(2, streamId);
        statement.setString(3, auditId);
        statement.setString(4, startDate);
        statement.setString(5, endDate);
        return statement;
    }

    /**
     * Aggregate by time dim
     */
    private List<AuditVO> aggregateByTimeDim(List<AuditVO> auditVOList, TimeStaticsDim timeStaticsDim) {
        List<AuditVO> result;
        switch (timeStaticsDim) {
            case HOUR:
                result = doAggregate(auditVOList, HOUR_FORMAT);
                break;
            case DAY:
                result = doAggregate(auditVOList, DAY_FORMAT);
                break;
            default:
                result = auditVOList;
                break;
        }
        return result;
    }

    /**
     * Execute the aggregate by the given time format
     */
    private List<AuditVO> doAggregate(List<AuditVO> auditVOList, String format) {
        List<AuditVO> result = new ArrayList<>();
        for (AuditVO auditVO : auditVOList) {
            AuditVO statInfo = new AuditVO();
            ConcurrentHashMap<String, AtomicLong> countMap = new ConcurrentHashMap<>();
            statInfo.setAuditId(auditVO.getAuditId());
            statInfo.setNodeType(auditVO.getNodeType());
            for (AuditInfo auditInfo : auditVO.getAuditSet()) {
                String statKey = formatLogTime(auditInfo.getLogTs(), format);
                if (statKey == null) {
                    continue;
                }
                if (countMap.get(statKey) == null) {
                    countMap.put(statKey, new AtomicLong(0));
                }
                countMap.get(statKey).addAndGet(auditInfo.getCount());
            }

            List<AuditInfo> auditInfoList = new LinkedList<>();
            for (Map.Entry<String, AtomicLong> entry : countMap.entrySet()) {
                AuditInfo auditInfoStat = new AuditInfo();
                auditInfoStat.setLogTs(entry.getKey());
                auditInfoStat.setCount(entry.getValue().get());
                auditInfoList.add(auditInfoStat);
            }
            statInfo.setAuditSet(auditInfoList);
            result.add(statInfo);
        }
        return result;
    }

    /**
     * Format the log time
     */
    private String formatLogTime(String dateString, String format) {
        String formatDateString = null;
        try {
            SimpleDateFormat formatter = new SimpleDateFormat(format);
            Date date = formatter.parse(dateString);
            formatDateString = formatter.format(date);
        } catch (Exception e) {
            LOGGER.error("format lot time exception", e);
        }
        return formatDateString;
    }
}
