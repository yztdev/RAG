package com.yizhaoqi.smartpai.service.search;

import com.yizhaoqi.smartpai.config.ParentContextProperties;
import com.yizhaoqi.smartpai.entity.SearchResult;
import com.yizhaoqi.smartpai.model.DocumentVector;
import com.yizhaoqi.smartpai.repository.DocumentVectorRepository;
import com.yizhaoqi.smartpai.service.chunk.ChunkTextMerger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 父子检索（small-to-big）：检索在小分块上做，命中后从 MySQL 取同一文件里前后相邻的分块拼成父块交给模型。
 * <ul>
 *     <li>父块从命中分块开始，向后、向前交替各扩展一个相邻分块，直到超过单个父块字符上限或邻居用完；</li>
 *     <li>后面的命中如果已经落在前面某个父块里，直接复用那个父块（parentKey 相同），提示词里只发一次；</li>
 *     <li>所有父块总字符数受 maxTotalChars 约束，预算用完后剩余命中只给小分块；</li>
 *     <li>引用、页码、锚点和证据片段仍然来自命中的小分块，父块只影响模型看到的上下文。</li>
 * </ul>
 * 相邻分块来自命中分块所在的同一文件，文件级权限已经在检索时校验过。
 */
@Service
public class ParentContextExpander {

    private static final Logger logger = LoggerFactory.getLogger(ParentContextExpander.class);

    private final ParentContextProperties properties;
    private final DocumentVectorRepository documentVectorRepository;

    public ParentContextExpander(ParentContextProperties properties, DocumentVectorRepository documentVectorRepository) {
        this.properties = properties;
        this.documentVectorRepository = documentVectorRepository;
    }

    /**
     * @param hits 检索结果（按排名顺序）
     * @return 新列表，命中能扩展父块的元素替换为带 parentContext 的副本；关闭或无结果时原样返回
     */
    public List<SearchResult> expand(List<SearchResult> hits) {
        if (!properties.isEnabled() || hits == null || hits.isEmpty()) {
            return hits;
        }
        int remaining = Math.max(properties.getMaxTotalChars(), 0);
        List<ParentBlock> blocks = new ArrayList<>();
        List<SearchResult> expanded = new ArrayList<>(hits.size());
        for (SearchResult hit : hits) {
            if (hit == null || hit.getFileMd5() == null || hit.getChunkId() == null) {
                expanded.add(hit);
                continue;
            }
            Optional<ParentBlock> shared = blocks.stream().filter(block -> block.covers(hit)).findFirst();
            if (shared.isPresent()) {
                expanded.add(hit.withParentContext(shared.get().text(), shared.get().key()));
                continue;
            }
            int cap = Math.min(Math.max(properties.getMaxCharsPerHit(), 0), remaining);
            Optional<ParentBlock> block = cap <= 0 ? Optional.empty() : buildBlock(hit, cap);
            if (block.isEmpty()) {
                expanded.add(hit);
                continue;
            }
            blocks.add(block.get());
            remaining -= block.get().text().length();
            expanded.add(hit.withParentContext(block.get().text(), block.get().key()));
        }
        logger.debug("父块扩展完成: hits={}, parentBlocks={}, remainingChars={}", hits.size(), blocks.size(), remaining);
        return expanded;
    }

    private Optional<ParentBlock> buildBlock(SearchResult hit, int cap) {
        int neighbours = Math.max(properties.getNeighbourChunks(), 0);
        if (neighbours == 0) {
            return Optional.empty();
        }
        List<DocumentVector> window;
        try {
            window = documentVectorRepository.findByFileMd5AndChunkIdBetweenOrderByChunkIdAsc(
                    hit.getFileMd5(), hit.getChunkId() - neighbours, hit.getChunkId() + neighbours);
        } catch (Exception e) {
            logger.warn("读取相邻分块失败，只使用命中分块: fileMd5={}, chunkId={}, error={}",
                    hit.getFileMd5(), hit.getChunkId(), e.getMessage());
            return Optional.empty();
        }
        int anchor = indexOf(window, hit.getChunkId());
        if (anchor < 0 || length(window, anchor, anchor) > cap) {
            return Optional.empty();
        }

        int lo = anchor;
        int hi = anchor;
        boolean canGrowAfter = true;
        boolean canGrowBefore = true;
        while (canGrowAfter || canGrowBefore) {
            if (canGrowAfter) {
                canGrowAfter = hi + 1 < window.size() && length(window, lo, hi + 1) <= cap;
                hi = canGrowAfter ? hi + 1 : hi;
            }
            if (canGrowBefore) {
                canGrowBefore = lo - 1 >= 0 && length(window, lo - 1, hi) <= cap;
                lo = canGrowBefore ? lo - 1 : lo;
            }
        }
        if (lo == hi) {
            return Optional.empty();
        }
        String key = hit.getFileMd5() + "#" + window.get(lo).getChunkId() + "-" + window.get(hi).getChunkId();
        return Optional.of(new ParentBlock(hit.getFileMd5(), window.get(lo).getChunkId(), window.get(hi).getChunkId(),
                merge(window, lo, hi), key));
    }

    private static int indexOf(List<DocumentVector> window, int chunkId) {
        for (int i = 0; i < window.size(); i++) {
            if (window.get(i).getChunkId() != null && window.get(i).getChunkId() == chunkId) {
                return i;
            }
        }
        return -1;
    }

    private static int length(List<DocumentVector> window, int lo, int hi) {
        return merge(window, lo, hi).length();
    }

    private static String merge(List<DocumentVector> window, int lo, int hi) {
        return ChunkTextMerger.merge(window.subList(lo, hi + 1).stream().map(DocumentVector::getTextContent).toList());
    }

    private record ParentBlock(String fileMd5, int fromChunkId, int toChunkId, String text, String key) {
        boolean covers(SearchResult hit) {
            return fileMd5.equals(hit.getFileMd5()) && hit.getChunkId() >= fromChunkId && hit.getChunkId() <= toChunkId;
        }
    }
}
