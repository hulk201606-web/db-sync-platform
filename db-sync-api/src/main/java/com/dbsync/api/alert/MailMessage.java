package com.dbsync.api.alert;

import java.util.List;

/**
 * 一封待发送的邮件（最小必要字段）。
 *
 * <p>为什么只留 {@code to/subject/text}：告警邮件的职责是"把异常说清楚并给出跳转入口"，
 * 不需要附件/抄送/富文本。字段越少，{@link MailTransport} 的实现越好写、
 * 测试替身越好断言；真要发富文本时再扩，而不是一开始就留一堆没人用的字段。
 *
 * @param to      收件人列表（不可变；空列表表示"没配收件人"，调用方应视为未配置）
 * @param subject 主题（必须含规则编码，便于邮件侧过滤/自动分派）
 * @param text    正文（纯文本：规则、任务、当前值/阈值、触发时间、跳转链接）
 */
public record MailMessage(List<String> to, String subject, String text) {

    /**
     * 构造：收件人列表做防御性拷贝（null 归一为空列表）。
     */
    public MailMessage {
        to = to == null ? List.of() : List.copyOf(to);
    }
}
