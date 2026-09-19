package com.mosaicgem.plugin.model;

import java.util.List;
import java.util.Map;

/**
 * 一件装备上已镶嵌的宝石实例。
 *
 * @param id         宝石内部名
 * @param instanceId 实例唯一标识
 * @param values     生成时固定的随机数（名 -> 格式化后的值）
 * @param external   生成时固定的外部值（供外部插件取用，名 -> 求值后的字符串）
 * @param lines      实际注入到装备 lore 的属性行（拆卸时按此移除）
 */
public record SocketedGem(
        String id,
        String instanceId,
        Map<String, String> values,
        Map<String, String> external,
        List<String> lines
) {
}
