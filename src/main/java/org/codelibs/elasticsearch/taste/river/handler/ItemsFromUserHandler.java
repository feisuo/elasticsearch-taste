package org.codelibs.elasticsearch.taste.river.handler;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.apache.mahout.cf.taste.common.TasteException;
import org.apache.mahout.cf.taste.eval.RecommenderBuilder;
import org.apache.mahout.cf.taste.impl.common.LongPrimitiveIterator;
import org.apache.mahout.cf.taste.model.DataModel;
import org.apache.mahout.cf.taste.recommender.Recommender;
import org.codelibs.elasticsearch.taste.model.ElasticsearchDataModel;
import org.codelibs.elasticsearch.taste.model.IndexInfo;
import org.codelibs.elasticsearch.taste.recommender.UserBasedRecommenderBuilder;
import org.codelibs.elasticsearch.taste.service.TasteService;
import org.codelibs.elasticsearch.taste.worker.RecommendedItemsWorker;
import org.codelibs.elasticsearch.taste.writer.ItemWriter;
import org.codelibs.elasticsearch.util.admin.ClusterUtils;
import org.codelibs.elasticsearch.util.io.IOUtils;
import org.codelibs.elasticsearch.util.settings.SettingsUtils;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.cache.Cache;
import org.elasticsearch.common.cache.CacheBuilder;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.river.RiverSettings;

public class ItemsFromUserHandler extends RecommendationHandler {

    public ItemsFromUserHandler(final RiverSettings settings,
            final Client client, final TasteService tasteService) {
        super(settings, client, tasteService);
    }

    @Override
    public void execute() {
        final int numOfItems = SettingsUtils.get(rootSettings, "num_of_items",
                10);
        final int maxDuration = SettingsUtils.get(rootSettings, "max_duration",
                0);
        final int numOfThreads = getNumOfThreads();

        final Map<String, Object> indexInfoSettings = SettingsUtils.get(
                rootSettings, "index_info");
        final IndexInfo indexInfo = new IndexInfo(indexInfoSettings);

        final Map<String, Object> modelInfoSettings = SettingsUtils.get(
                rootSettings, "data_model");
        final ElasticsearchDataModel dataModel = createDataModel(client,
                indexInfo, modelInfoSettings);

        ClusterUtils.waitForAvailable(client, indexInfo.getUserIndex(),
                indexInfo.getItemIndex(), indexInfo.getPreferenceIndex(),
                indexInfo.getRecommendationIndex());

        final UserBasedRecommenderBuilder recommenderBuilder = new UserBasedRecommenderBuilder(
                indexInfo, rootSettings);

        final ItemWriter writer = createRecommendedItemsWriter(indexInfo,
                rootSettings);

        compute(dataModel, recommenderBuilder, writer, numOfItems,
                numOfThreads, maxDuration);
    }

    protected void compute(final DataModel dataModel,
            final RecommenderBuilder recommenderBuilder,
            final ItemWriter writer, final int numOfRecommendedItems,
            final int degreeOfParallelism, final int maxDuration) {
        final ExecutorService executorService = Executors
                .newFixedThreadPool(degreeOfParallelism);

        Recommender recommender = null;
        try {
            recommender = recommenderBuilder.buildRecommender(dataModel);

            logger.info("Recommender: {}", recommender);
            logger.info("NumOfRecommendedItems: {}", numOfRecommendedItems);
            logger.info("MaxDuration: {}", maxDuration);

            final LongPrimitiveIterator userIDs = dataModel.getUserIDs();

            for (int n = 0; n < degreeOfParallelism; n++) {
                final RecommendedItemsWorker worker = new RecommendedItemsWorker(
                        n, recommender, userIDs, numOfRecommendedItems, writer);
                executorService.execute(worker);
            }

            waitFor(executorService, maxDuration);
        } catch (final TasteException e) {
            logger.error("Recommender {} is failed.", e, recommender);
        } finally {
            IOUtils.closeQuietly(writer);
        }

    }

    protected ItemWriter createRecommendedItemsWriter(
            final IndexInfo indexInfo, final Map<String, Object> rootSettings) {
        final ItemWriter writer = new ItemWriter(client,
                indexInfo.getRecommendationIndex(),
                indexInfo.getRecommendationType(), indexInfo.getUserIdField());
        writer.setTargetIndex(indexInfo.getUserIndex());
        writer.setTargetType(indexInfo.getUserType());
        writer.setItemIndex(indexInfo.getItemIndex());
        writer.setItemType(indexInfo.getItemType());
        writer.setItemIdField(indexInfo.getItemIdField());
        writer.setItemsField(indexInfo.getItemsField());
        writer.setValueField(indexInfo.getValueField());
        writer.setTimestampField(indexInfo.getTimestampField());
        try {
            final XContentBuilder builder = XContentFactory.jsonBuilder()//
                    .startObject()//
                    .startObject(indexInfo.getRecommendationType())//
                    .startObject("properties")//

                    // @timestamp
                    .startObject(indexInfo.getTimestampField())//
                    .field("type", "date")//
                    .field("format", "dateOptionalTime")//
                    .endObject()//

                    // user_id
                    .startObject(indexInfo.getUserIdField())//
                    .field("type", "long")//
                    .endObject()//

                    // items
                    .startObject(indexInfo.getItemsField())//
                    .startObject("properties")//

                    // item_id
                    .startObject(indexInfo.getItemIdField())//
                    .field("type", "long")//
                    .endObject()//

                    // value
                    .startObject(indexInfo.getValueField())//
                    .field("type", "double")//
                    .endObject()//

                    .endObject()//
                    .endObject()//

                    .endObject()//
                    .endObject()//
                    .endObject();
            writer.setMapping(builder);
        } catch (final IOException e) {
            logger.info("Failed to create a mapping {}/{}.", e,
                    indexInfo.getReportIndex(), indexInfo.getReportType());
        }

        final Map<String, Object> writerSettings = SettingsUtils.get(
                rootSettings, "writer");
        final boolean verbose = SettingsUtils.get(writerSettings, "verbose",
                false);
        if (verbose) {
            writer.setVerbose(verbose);
            final int maxCacheSize = SettingsUtils.get(writerSettings,
                    "cache_size", 1000);
            final Cache<Long, Map<String, Object>> cache = CacheBuilder
                    .newBuilder().maximumSize(maxCacheSize).build();
            writer.setCache(cache);
        }

        writer.open();

        return writer;
    }

    @Override
    public void close() {
    }
}