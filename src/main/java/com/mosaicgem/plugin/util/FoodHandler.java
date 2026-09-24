package com.mosaicgem.plugin.util;

import com.mosaicgem.plugin.config.GemDefinition;
import com.mosaicgem.plugin.model.SocketedGem;

import java.util.ArrayList;
import java.util.List;

/**
 * food：饱食度维持词条（宝石装备期间把食物值维持在设定上限内）。
 *
 * <p>语义：只补不压 —— 食物值低于上限时每次补 step（封顶上限），
 * 玩家自己吃满（20）时完全不干预；食物 ≤ 上限（默认 17）时原版自然回血条件
 * （食物 ≥ 18）不成立，所以"卡在不能回血的区间"，同时 >6 仍可冲刺、不会饿到掉血。
 *
 * <p>属性行格式（可用 ${} 表达式）：
 * <pre>
 * 17          # 上限 17，每次补 4（默认步长）
 * 17: 4       # 上限 17，每次补 4
 * </pre>
 * 解析时从右往左取数字：最后一个是"每次补充"，前一个是"上限"；
 * 因此行首多写一层数字标识符（如 {@code 1: 17: 4}）也不影响结果。
 */
public final class FoodHandler implements BuffTypeHandler {

    private static final int DEFAULT_CAP = 17;
    private static final int DEFAULT_STEP = 4;

    /** 行解析结果：上限 / 每次补充量。 */
    public record FoodSpec(int cap, int step) {
    }

    @Override
    public String id() {
        return ItemFactory.BUFF_TYPE_FOOD;
    }

    @Override
    public List<String> valueLines(SocketedGem gem, ItemFactory factory) {
        GemDefinition definition = factory.configs().getGem(gem.id());
        if (definition == null) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String raw : definition.attributeLinesOf(ItemFactory.BUFF_TYPE_FOOD)) {
            FoodSpec spec = parse(factory.resolve(raw, gem.values()));
            if (spec == null) {
                continue;
            }
            result.add("饱食度维持：上限 " + spec.cap() + "（每次 +" + spec.step() + "）");
        }
        return result;
    }

    /** 解析一行饱食度声明：{@code '<上限>[: <每次补充>]'}；非法返回 null。 */
    public static FoodSpec parse(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        String[] fields = line.trim().split(":", -1);
        List<Long> numbers = new ArrayList<>(2);
        for (int i = fields.length - 1; i >= 0 && numbers.size() < 2; i--) {
            Long value = toLongOrNull(fields[i].trim());
            if (value != null) {
                numbers.add(value);
            }
        }
        if (numbers.isEmpty()) {
            return null;
        }
        long cap = numbers.get(numbers.size() - 1);
        long step = numbers.size() > 1 ? numbers.get(0) : DEFAULT_STEP;
        if (cap <= 0) {
            return null;
        }
        return new FoodSpec((int) cap, (int) Math.max(1L, step));
    }

    private static Long toLongOrNull(String text) {
        if (text.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
