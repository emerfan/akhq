package org.akhq.controllers;

import io.micronaut.context.annotation.Value;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.*;
import io.swagger.v3.oas.annotations.Operation;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.akhq.configs.security.Role;
import org.akhq.models.AccessControl;
import org.akhq.models.Consumer;
import org.akhq.models.ConsumerGroup;
import org.akhq.models.TopicPartition;
import org.akhq.modules.AbstractKafkaWrapper;
import org.akhq.repositories.AccessControlListRepository;
import org.akhq.repositories.ConsumerGroupRepository;
import org.akhq.repositories.RecordRepository;
import org.akhq.security.annotation.AKHQSecured;
import org.akhq.utils.Pagination;
import org.akhq.utils.ResultPagedList;
import org.apache.kafka.common.resource.ResourceType;
import org.codehaus.httpcache4j.uri.URIBuilder;

import java.time.Instant;
import java.util.AbstractMap;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import jakarta.inject.Inject;

@AKHQSecured(resource = Role.Resource.CONSUMER_GROUP, action = Role.Action.READ)
@Controller("/api/{cluster}/group")
public class GroupController extends AbstractController {
    private final AbstractKafkaWrapper kafkaWrapper;
    private final ConsumerGroupRepository consumerGroupRepository;
    private final RecordRepository recordRepository;
    private final AccessControlListRepository aclRepository;

    @Value("${akhq.pagination.page-size}")
    private Integer pageSize;

    @Inject
    public GroupController(
        AbstractKafkaWrapper kafkaWrapper,
        ConsumerGroupRepository consumerGroupRepository,
        RecordRepository recordRepository,
        AccessControlListRepository aclRepository
    ) {
        this.kafkaWrapper = kafkaWrapper;
        this.consumerGroupRepository = consumerGroupRepository;
        this.recordRepository = recordRepository;
        this.aclRepository = aclRepository;
    }

    @Get
    @Operation(tags = {"consumer group"}, summary = "List all consumer groups")
    public ResultPagedList<ConsumerGroup> list(HttpRequest<?> request, String cluster, Optional<String> search, Optional<Integer> page) throws ExecutionException, InterruptedException {
        checkIfClusterAllowed(cluster);

        URIBuilder uri = URIBuilder.fromURI(request.getUri());
        Pagination pagination = new Pagination(pageSize, uri, page.orElse(1));

        return ResultPagedList.of(this.consumerGroupRepository.list(cluster, pagination, search, buildUserBasedResourceFilters(cluster)));
    }

    @Get("{groupName}")
    @Operation(tags = {"consumer group"}, summary = "Retrieve a consumer group")
    public ConsumerGroup home(String cluster, String groupName) throws ExecutionException, InterruptedException {
        checkIfClusterAndResourceAllowed(cluster, groupName);

        return this.consumerGroupRepository.findByName(cluster, groupName, buildUserBasedResourceFilters(cluster));
    }

    @Get("{groupName}/offsets")
    @Operation(tags = {"consumer group"}, summary = "Retrieve a consumer group offsets")
    public List<TopicPartition.ConsumerGroupOffset> offsets(String cluster, String groupName) throws ExecutionException, InterruptedException {
        checkIfClusterAndResourceAllowed(cluster, groupName);

        return this.consumerGroupRepository.findByName(cluster, groupName, buildUserBasedResourceFilters(cluster)).getOffsets();
    }

    @Get("{groupName}/members")
    @Operation(tags = {"consumer group"}, summary = "Retrieve a consumer group members")
    public List<Consumer> members(String cluster, String groupName) throws ExecutionException, InterruptedException {
        checkIfClusterAndResourceAllowed(cluster, groupName);

        return this.consumerGroupRepository.findByName(cluster, groupName, buildUserBasedResourceFilters(cluster)).getMembers();
    }

    @Get("{groupName}/acls")
    @Operation(tags = {"consumer group"}, summary = "Retrieve a consumer group acls")
    public List<AccessControl> acls(String cluster, String groupName) throws ExecutionException, InterruptedException {
        checkIfClusterAndResourceAllowed(cluster, groupName);

        return aclRepository.findByResourceType(cluster, ResourceType.GROUP, groupName);
    }

    @Get("topics")
    @Operation(tags = {"consumer group"}, summary = "Retrieve consumer group for list of topics")
    public List filterByTopics(String cluster, Optional<List<String>> topics) {
        checkIfClusterAllowed(cluster);

        return topics.map(
                topicsName -> {
                    try {
                        return this.consumerGroupRepository.findByTopics(cluster, topicsName,
                            buildUserBasedResourceFilters(cluster));
                    } catch (ExecutionException | InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }
        ).orElse(Collections.EMPTY_LIST);
    }

    @AKHQSecured(resource = Role.Resource.CONSUMER_GROUP, action = Role.Action.UPDATE_OFFSET)
    @Post(value = "{groupName}/offsets", consumes = MediaType.APPLICATION_JSON)
    @Operation(tags = {"consumer group"}, summary = "Update consumer group offsets")
    public HttpResponse<?> offsets(
        String cluster,
        String groupName,
        @Body List<OffsetsUpdate> offsets
    ) {
        checkIfClusterAndResourceAllowed(cluster, groupName);

        this.consumerGroupRepository.updateOffsets(
            cluster,
            groupName,
            offsets
                .stream()
                .map(r -> new AbstractMap.SimpleEntry<>(
                        new TopicPartition(r.getTopic(), r.getPartition()),
                        r.getOffset()
                    )
                )
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue))
        );

        return HttpResponse.noContent();
    }

    @AKHQSecured(resource = Role.Resource.CONSUMER_GROUP, action = Role.Action.UPDATE_OFFSET)
    @Get("{groupName}/offsets/start")
    @Operation(tags = {"consumer group"}, summary = "Retrive consumer group offsets by timestamp")
    public List<RecordRepository.TimeOffset> offsetsStart(String cluster, String groupName, Instant timestamp) throws ExecutionException, InterruptedException {
        checkIfClusterAndResourceAllowed(cluster, groupName);

        ConsumerGroup group = this.consumerGroupRepository.findByName(
            cluster, groupName, buildUserBasedResourceFilters(cluster));

        return recordRepository.getOffsetForTime(
            cluster,
            group.getOffsets()
                .stream()
                .map(r -> new TopicPartition(r.getTopic(), r.getPartition()))
                .collect(Collectors.toList()),
            timestamp.toEpochMilli()
        );
    }

    @AKHQSecured(resource = Role.Resource.CONSUMER_GROUP, action = Role.Action.DELETE)
    @Delete("{groupName}")
    @Operation(tags = {"consumer group"}, summary = "Delete a consumer group")
    public HttpResponse<?> delete(String cluster, String groupName) throws ExecutionException, InterruptedException {
        checkIfClusterAndResourceAllowed(cluster, groupName);

        this.kafkaWrapper.deleteConsumerGroups(cluster, groupName);

        return HttpResponse.noContent();
    }

    @AKHQSecured(resource = Role.Resource.CONSUMER_GROUP, action = Role.Action.DELETE_OFFSET)
    @Delete("{groupName}/topic/{topicName}")
    @Operation(tags = {"consumer group"}, summary = "Delete group offsets of given topic")
    public HttpResponse<?> deleteConsumerGroupOffsets(String cluster, String groupName, String topicName) throws ExecutionException {
        checkIfClusterAndResourceAllowed(cluster, groupName);

        this.kafkaWrapper.deleteConsumerGroupOffsets(cluster, groupName, topicName);
        return HttpResponse.noContent();
    }

    @NoArgsConstructor
    @AllArgsConstructor
    @Getter
    public static class OffsetsUpdate {
        private String topic;
        private int partition;
        private long offset;
    }
}
