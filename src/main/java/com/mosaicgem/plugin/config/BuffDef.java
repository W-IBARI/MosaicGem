package com.mosaicgem.plugin.config;

import java.util.List;

/**
 * 宝石词条：一种加成方式（buffType）+ 该方式下的属性行列表。
 * 一颗宝石可含多条词条（v1.2+）：如 ce_attribute + enchant + vanilla_attribute 同时生效。
 * 词条内部行格式与旧版 attribute 完全一致，直接复用各 BuffTypeHandler 的解析逻辑。
 */
public record BuffDef(String type, List<String> attribute) {

    public BuffDef {
        attribute = attribute == null ? List.of() : List.copyOf(attribute);
    }
}
