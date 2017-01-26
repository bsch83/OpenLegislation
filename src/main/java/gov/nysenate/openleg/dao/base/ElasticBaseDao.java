package gov.nysenate.openleg.dao.base;

import com.google.common.primitives.Ints;
import gov.nysenate.openleg.model.search.SearchResult;
import gov.nysenate.openleg.model.search.SearchResults;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.action.admin.indices.delete.DeleteIndexRequest;
import org.elasticsearch.action.admin.indices.exists.indices.IndicesExistsRequest;
import org.elasticsearch.action.bulk.BulkRequestBuilder;
import org.elasticsearch.action.delete.DeleteRequestBuilder;
import org.elasticsearch.action.get.GetRequestBuilder;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.action.index.IndexRequestBuilder;
import org.elasticsearch.action.search.SearchRequestBuilder;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.SearchType;
import org.elasticsearch.action.update.UpdateRequestBuilder;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.index.IndexNotFoundException;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.fetch.subphase.highlight.HighlightBuilder;
import org.elasticsearch.search.rescore.RescoreBuilder;
import org.elasticsearch.search.sort.SortBuilder;
import org.springframework.beans.factory.annotation.Autowired;

import javax.annotation.PostConstruct;
import java.math.BigDecimal;
import java.util.*;
import java.util.function.Function;

/**
 * Base class for Elastic Search layer classes to inherit common functionality from.
 */
public abstract class ElasticBaseDao
{
    private static final Logger logger = LogManager.getLogger(ElasticBaseDao.class);

    @Autowired
    protected Client searchClient;

    @PostConstruct
    private void init() {
        createIndices();
    }

    /** --- Public methods --- */

    public void createIndices() {
        getIndices().stream()
                .filter(index -> !indicesExist(index))
                .forEach(this::createIndex);
    }

    public void purgeIndices() {
        getIndices().forEach(this::deleteIndex);
    }

    /** --- Abstract methods --- */

    /**
     * Returns a list containing the names of all indices used by the inheriting Dao
     *
     * @return
     */
    protected abstract List<String> getIndices();

    /** --- Common Elastic Search methods --- */

    /**
     * Generates a typical search request that involves a query, filter, sort string, and a limit + offset
     * @see #getSearchRequest(String, QueryBuilder, QueryBuilder, List, LimitOffset)
     *
     * Highlighting, rescoring, and full source response are not supported via this method.
     */
    protected SearchRequestBuilder getSearchRequest(String indexName, QueryBuilder query, QueryBuilder postFilter,
                                                    List<SortBuilder> sort, LimitOffset limitOffset) {
        return getSearchRequest(Collections.singleton(indexName),
                query, postFilter, null, null, sort, limitOffset, false);
    }

    /**
     * Generates a typical search request that involves a query, filter, sort string, and a limit + offset
     * @see #getSearchRequest(String, QueryBuilder, QueryBuilder, List, LimitOffset)
     *
     * This method causes the search request to return the source data that was searched
     */
    protected SearchRequestBuilder getFetchingSearchRequest(String indexName,
                                                            QueryBuilder query, QueryBuilder postFilter,
                                                            List<SortBuilder> sort, LimitOffset limitOffset) {
        return getSearchRequest(Collections.singleton(indexName),
                query, postFilter, null, null, sort, limitOffset, true);
    }

    /**
     * Generates a SearchRequest with support for various functions.
     *
     * @param indexNames - The name of the index to search.
     * @param query - The QueryBuilder instance to perform the search with.
     * @param postFilter - Optional FilterBuilder to filter out the results.
     * @param highlightedFields - Optional list of field names to return as highlighted fields.
     * @param rescorer - Optional rescorer that can be used to fine tune the query ranking.
     * @param sort - List of SortBuilders specifying the desired sorting
     * @param limitOffset - Restrict the number of results returned as well as paginate.
     * @param fetchSource - Will return the indexed source fields when set to true
     * @return SearchRequestBuilder
     */
    protected SearchRequestBuilder getSearchRequest(Set<String> indexNames, QueryBuilder query, QueryBuilder postFilter,
                                                    List<HighlightBuilder.Field> highlightedFields, RescoreBuilder rescorer,
                                                    List<SortBuilder> sort, LimitOffset limitOffset, boolean fetchSource) {
        SearchRequestBuilder searchBuilder = searchClient.prepareSearch()
                .setIndices(indexNames.toArray(new String[indexNames.size()]))
                .setSearchType(SearchType.QUERY_THEN_FETCH)
                .setQuery(query)
                .setFrom(limitOffset.getOffsetStart() - 1)
                .setSize((limitOffset.hasLimit()) ? limitOffset.getLimit() : Integer.MAX_VALUE)
                .setMinScore(0.05f)
                .setFetchSource(fetchSource);

        if (highlightedFields != null) {
            HighlightBuilder highlightBuilder = new HighlightBuilder();
            highlightedFields.forEach(highlightBuilder::field);
            searchBuilder.highlighter(highlightBuilder);
        }
        if (rescorer != null) {
            searchBuilder.setRescorer(rescorer);
        }
        // Post filters take effect after the search is completed
        if (postFilter != null) {
            searchBuilder.setPostFilter(postFilter);
        }
        // Add the sort by fields
        sort.forEach(searchBuilder::addSort);
        logger.debug("{}", searchBuilder);
        return searchBuilder;
    }

    /**
     * Generates a SearchRequest with support for following parameters.
     *
     * @param indices - The names of the indices to search.
     * @param types - The names of the types in indices.
     * @param query - The QueryBuilder instance to perform the search with.
     * @param postFilter - The QueryBuilder instance to filter the search results with.
     * @param sorts - List of SortBuilders specifying the desired sorting
     * @param setScroll - Boolean Parameter to set Scroll.
     * @param size - Integer to set
     * @return SearchRequestBuilder
     */
    protected SearchRequestBuilder getSearchRequest(Set<String> indices, Set<String> types, QueryBuilder query, QueryBuilder postFilter,
                                                    List<SortBuilder> sorts,
                                                    boolean setScroll, int size){
        SearchRequestBuilder searchBuilder = searchClient.prepareSearch()
                .setIndices(indices.stream().toArray(String[]::new))
                .setTypes(types.stream().toArray(String[]::new))
                .setQuery(query);
        if(postFilter != null)
            searchBuilder.setPostFilter(postFilter);
        if(setScroll)
            searchBuilder.setScroll(new TimeValue(60000));
        if(sorts != null)
            sorts.forEach(searchBuilder::addSort);
        searchBuilder.setSize(size > 0 ? size : 100);
        return searchBuilder;
    }

    protected IndexRequestBuilder getIndexRequest(String index, String type, String Id){
        IndexRequestBuilder indexBuilder = searchClient.prepareIndex()
                .setIndex(index)
                .setType(type)
                .setId(Id);

        return indexBuilder;
    }

    /**
     * Extracts search results from a search response
     *
     * template <R> is the desired return type
     *
     * @param response a SearchResponse generated by a SearchRequest
     * @param limitOffset the LimitOffset used in the SearchRequest
     * @param hitMapper a function that maps a SearchHit to the desired return type R
     * @return SearchResults<R>
     */
    protected <R> SearchResults<R> getSearchResults(SearchResponse response, LimitOffset limitOffset,
                                                    Function<SearchHit, R> hitMapper) {
        List<SearchResult<R>> resultList = new ArrayList<>();
        for (SearchHit hit : response.getHits().hits()) {
            SearchResult<R> result = new SearchResult<>(
                    hitMapper.apply(hit), // Result
                    (!Float.isNaN(hit.getScore())) ? BigDecimal.valueOf(hit.getScore()) : BigDecimal.ONE, // Rank
                    hit.getHighlightFields()); // Highlights
            resultList.add(result);
        }
        return new SearchResults<>(Ints.checkedCast(response.getHits().getTotalHits()), resultList, limitOffset);
    }

    /**
     * Extracts search results from a search response
     *
     * template <R> is the desired return type
     *
     * @param response a SearchResponse generated by a SearchRequest
     * @param limitOffset the LimitOffset used in the SearchRequest
     * @param hitMapper a function that maps a SearchHit to the desired return type R
     * @return SearchResults<R>
     */
    protected <R> SearchResults<R> getSearchResults(List<SearchHit> searchHits, LimitOffset limitOffset,
                                                    Function<SearchHit, R> hitMapper) {
        List<SearchResult<R>> resultList = new ArrayList<>();
        for (SearchHit hit : searchHits) {
            SearchResult<R> result = new SearchResult<>(
                    hitMapper.apply(hit), // Result
                    (!Float.isNaN(hit.getScore())) ? BigDecimal.valueOf(hit.getScore()) : BigDecimal.ONE, // Rank
                    hit.getHighlightFields()); // Highlights
            resultList.add(result);
        }
        return new SearchResults<>(Ints.checkedCast(searchHits.size()), resultList, limitOffset);
    }

    /**
     * Performs a get request on the given index for the document designated by the given type and id
     * returns an optional that is empty if a document does not exist for the given request parameters
     * @param index String - a search index
     * @param type String - a search type
     * @param id String - the id of the desired document
     * @param responseMapper Function<GetResponse, T> - a function that maps the response to the desired class
     * @param <T> The type to be returned
     * @return Optional<T></T>
     */
    protected <T> Optional<T> getRequest(String index, String type, String id, Function<GetResponse, T> responseMapper) {
        GetResponse getResponse = searchClient.prepareGet(index, type, id).execute().actionGet();
        if (getResponse.isExists()) {
            return Optional.of(responseMapper.apply(getResponse));
        }
        return Optional.empty();
    }

    protected GetRequestBuilder getRequest(String index, String type, String Id){
        GetRequestBuilder getBuilder = searchClient.prepareGet()
                .setIndex(index)
                .setType(type)
                .setId(Id);
        return getBuilder;
    }

    protected UpdateRequestBuilder getUpdateRequest(String index, String type, String Id){
        UpdateRequestBuilder updateBuilder = searchClient.prepareUpdate()
                .setIndex(index)
                .setType(type)
                .setId(Id);
        return updateBuilder;
    }

    /**
     * Performs a bulk request execution while making sure that the bulk request is actually valid to
     * prevent exceptions.
     * @param bulkRequest BulkRequestBuilder
     */
    protected void safeBulkRequestExecute(BulkRequestBuilder bulkRequest) {
        if (bulkRequest != null && bulkRequest.numberOfActions() > 0) {
            bulkRequest.execute().actionGet();
        }
    }

    protected void deleteEntry(String indexName, String type, String id) {
        DeleteRequestBuilder request = searchClient.prepareDelete();
        request.setIndex(indexName);
        request.setType(type);
        request.setId(id);
        request.execute().actionGet();

    }

    protected boolean indicesExist(String... indices) {
        return searchClient.admin().indices().exists(new IndicesExistsRequest(indices)).actionGet().isExists();
    }

    protected void createIndex(String indexName) {
        searchClient.admin().indices().prepareCreate(indexName).execute().actionGet();
    }

    protected void deleteIndex(String index) {
        try {
            logger.info("Deleting search index {}", index);
            searchClient.admin().indices().delete(new DeleteIndexRequest(index)).actionGet();
        }
        catch (IndexNotFoundException ex) {
            logger.info("Cannot delete index {} because it doesn't exist.", index);
        }
    }
}
