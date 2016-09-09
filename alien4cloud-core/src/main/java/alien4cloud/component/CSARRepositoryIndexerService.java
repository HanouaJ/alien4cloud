package alien4cloud.component;

import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Resource;

import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.FilterBuilder;
import org.elasticsearch.index.query.FilterBuilders;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.mapping.ElasticSearchClient;
import org.springframework.stereotype.Component;

import alien4cloud.dao.ElasticSearchDAO;
import alien4cloud.dao.IGenericSearchDAO;
import alien4cloud.dao.model.GetMultipleDataResult;
import alien4cloud.exception.IndexingServiceException;
import alien4cloud.images.IImageDAO;
import alien4cloud.model.common.Tag;
import alien4cloud.model.components.CSARDependency;
import alien4cloud.model.components.IndexedInheritableToscaElement;
import alien4cloud.model.components.IndexedModelUtils;
import alien4cloud.model.components.IndexedToscaElement;
import alien4cloud.tosca.ArchiveImageLoader;
import alien4cloud.tosca.normative.ToscaType;
import alien4cloud.utils.MapUtil;
import alien4cloud.utils.VersionUtil;

import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

import static alien4cloud.utils.AlienUtils.safe;

@Component
public class CSARRepositoryIndexerService implements ICSARRepositoryIndexerService {
    @Resource(name = "alien-es-dao")
    private IGenericSearchDAO alienDAO;
    @Resource
    private ElasticSearchClient elasticSearchClient;
    @Resource
    private IImageDAO imageDAO;

    private void refreshIndexForSearching() {
        elasticSearchClient.getClient().admin().indices().prepareRefresh(ElasticSearchDAO.TOSCA_ELEMENT_INDEX).execute().actionGet();
    }

    @Override
    public Map<String, IndexedToscaElement> getArchiveElements(String archiveName, String archiveVersion) {
        return getArchiveElements(archiveName, archiveVersion, IndexedToscaElement.class);
    }

    @Override
    public <T extends IndexedToscaElement> Map<String, T> getArchiveElements(String archiveName, String archiveVersion, Class<T> type) {
        GetMultipleDataResult<T> elements = alienDAO.find(type, MapUtil.newHashMap(new String[]{"archiveName", "archiveVersion"},
                new String[][]{new String[]{archiveName}, new String[]{archiveVersion}}), Integer.MAX_VALUE);

        Map<String, T> elementsByIds = Maps.newHashMap();
        if (elements == null) {
            return elementsByIds;
        }

        for (T element : elements.getData()) {
            elementsByIds.put(element.getId(), element);
        }
        return elementsByIds;
    }

    @Override
    public void deleteElements(String archiveName, String archiveVersion) {

        FilterBuilder filter = FilterBuilders.boolFilter().must(FilterBuilders.termFilter("archiveName", archiveName))
                .must(FilterBuilders.termFilter("archiveVersion", archiveVersion));
        GetMultipleDataResult<IndexedToscaElement> result = alienDAO.search(IndexedToscaElement.class, null, null, filter, null, 0, Integer.MAX_VALUE);
        IndexedToscaElement[] elements = result.getData();

        // we need to delete each element and find the new highest version element
        for (IndexedToscaElement element : elements) {
            deleteElement(element);
        }

    }

    @Override
    public void indexInheritableElements(String archiveName, String archiveVersion, Map<String, ? extends IndexedInheritableToscaElement> archiveElements,
                                         Collection<CSARDependency> dependencies) {
        for (IndexedInheritableToscaElement element : safe(archiveElements).values()) {
            element.setLastUpdateDate(new Date());
            Date creationDate = element.getCreationDate() == null ? element.getLastUpdateDate() : element.getCreationDate();
            element.setCreationDate(creationDate);
            alienDAO.save(element);
            refreshIndexForSearching();
        }
    }

    @Override
    public void indexInheritableElement(String archiveName, String archiveVersion, IndexedInheritableToscaElement element,
                                        Collection<CSARDependency> dependencies) {
        // FIXME do we need all the merge in case of substitution ?
        element.setLastUpdateDate(new Date());
        Date creationDate = element.getCreationDate() == null ? element.getLastUpdateDate() : element.getCreationDate();
        element.setCreationDate(creationDate);
        if (element.getDerivedFrom() != null) {
            boolean deriveFromSimpleType = false;
            if (element.getDerivedFrom().size() == 1 && ToscaType.isSimple(element.getDerivedFrom().get(0))) {
                deriveFromSimpleType = true;
            }
            if (!deriveFromSimpleType) {
                Class<? extends IndexedInheritableToscaElement> indexedType = element.getClass();
                BoolQueryBuilder boolQueryBuilder = QueryBuilders.boolQuery();
                // Check dependencies
                if (dependencies != null) {
                    for (CSARDependency dependency : dependencies) {
                        addArchiveToQuery(boolQueryBuilder, element.getDerivedFrom().get(0), dependency.getName(), dependency.getVersion());
                    }
                }
                // Check in the archive it-self
                addArchiveToQuery(boolQueryBuilder, element.getDerivedFrom().get(0), archiveName, archiveVersion);
                IndexedInheritableToscaElement superElement = alienDAO.customFind(indexedType, boolQueryBuilder);
                if (superElement == null) {
                    throw new IndexingServiceException("Indexing service is in an inconsistent state, the super element [" + element.getDerivedFrom()
                            + "] is not found for element [" + element.getId() + "]");
                }
                IndexedModelUtils.mergeInheritableIndex(superElement, element);
            }
        }
        alienDAO.save(element);
        refreshIndexForSearching();
    }


    private static void addArchiveToQuery(BoolQueryBuilder boolQueryBuilder, String elementId, String archiveName, String archiveVersion) {
        QueryBuilder matchIdQueryBuilder = QueryBuilders.idsQuery().addIds(elementId + ":" + archiveVersion);
        QueryBuilder matchArchiveNameQueryBuilder = QueryBuilders.termQuery("archiveName", archiveName);
        boolQueryBuilder.should(QueryBuilders.boolQuery().must(matchIdQueryBuilder).must(matchArchiveNameQueryBuilder));
    }

    private void deleteElement(IndexedToscaElement element) {
        Tag iconTag = ArchiveImageLoader.getIconTag(element.getTags());
        if (iconTag != null) {
            imageDAO.delete(iconTag.getValue());
        }
        alienDAO.delete(element.getClass(), element.getId());
    }

    @Override
    public void deleteElements(Collection<IndexedToscaElement> elements) {
        for (IndexedToscaElement element : elements) {
            alienDAO.delete(element.getClass(), element.getId());
        }
    }
}