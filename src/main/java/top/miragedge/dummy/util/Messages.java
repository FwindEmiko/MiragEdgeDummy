package top.miragedge.dummy.util;

import org.bukkit.ChatColor;

import java.util.ArrayList;
import java.util.List;

/**
 * 消息工具：从 messages.yml 读取并支持 & 颜色代码与占位符替换。
 *
 * <p>设计约定：</p>
 * <ul>
 *   <li>全部消息中文，禁止 emoji（基岩版 Geyser 渲染为乱码）</li>
 *   <li>玩家向提示正文用灰色斜体 {@code §7§o}，与普通白色消息区分（服务器约定）</li>
 *   <li>前缀统一由 messages.yml 的 messages.global-prefix 提供</li>
 *   <li>{@code {xxx}} 形式占位符在运行时替换</li>
 * </ul>
 */
public class Messages {

    private final org.bukkit.configuration.file.FileConfiguration yml;

    public Messages(org.bukkit.configuration.file.FileConfiguration yml) {
        this.yml = yml;
    }

    /**
     * 取原始消息（已转颜色代码，未加前缀）。
     */
    public String raw(String path) {
        return colorize(yml.getString(path, ""));
    }

    /**
     * 取带前缀的消息。
     */
    public String withPrefix(String path) {
        String prefix = raw("messages.global-prefix");
        String msg = raw(path);
        if (prefix.isEmpty() || msg.isEmpty()) {
            return msg;
        }
        return prefix + " " + msg;
    }

    /**
     * 替换占位符：{a}->x, {b}->y ... 用法 {@code msg("{a}{b}", "a", v1, "b", v2)}。
     */
    public String fmt(String path, String... placeholders) {
        String s = withPrefix(path);
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            s = s.replace("{" + placeholders[i] + "}", placeholders[i + 1]);
        }
        return s;
    }

    /**
     * 伤害显示专用：{damage} 与 {hearts} 占位替换。
     */
    public String fmtDamage(double damage, double hearts, int precision) {
        String pattern = "%." + Math.max(0, precision) + "f";
        String d = String.format(java.util.Locale.ROOT, pattern, damage);
        String h = String.format(java.util.Locale.ROOT, pattern, hearts);
        return raw("messages.damage").replace("{damage}", d).replace("{hearts}", h);
    }

    /**
     * 读字符串配置并做颜色码转换；缺失时返回默认值。
     */
    public String getString(String path, String def) {
        return colorize(yml.getString(path, def));
    }

    /**
     * 读字符串列表配置，逐项做颜色码转换。
     */
    public List<String> getStringList(String path) {
        List<String> list = yml.getStringList(path);
        List<String> result = new ArrayList<>(list.size());
        for (String s : list) {
            result.add(colorize(s));
        }
        return result;
    }

    /**
     * ChatColor（legacy 字符串体系）在 26.2 已弃用，但本项目消息体系统一为 & 颜色码字符串
     * （与 messages.yml、服务器现有插件风格一致），换 Adventure Component 需全链路重构，故有意沿用。
     */
    @SuppressWarnings("deprecation")
    public static String colorize(String s) {
        if (s == null) {
            return "";
        }
        return ChatColor.translateAlternateColorCodes('&', s);
    }
}
