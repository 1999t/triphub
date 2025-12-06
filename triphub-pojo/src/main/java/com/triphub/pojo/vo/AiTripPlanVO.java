package com.triphub.pojo.vo;

import com.triphub.pojo.entity.Trip;
import lombok.Data;

/**
 * AI 行程规划返回结果 VO。
 * Response for AI-based trip planning, includes a generated trip and explanation.
 *
 * 包含：
 * - 生成并落库的 Trip 主信息；
 * - 一段由 LLM（或本地兜底逻辑）生成的推荐解释。
 */
@Data
public class AiTripPlanVO {

    private Trip trip;

    /**
     * 推荐解释文案，说明为什么该行程适合当前用户。
     * Simple explanation about why this trip is recommended.
     */
    private String explanation;
}



