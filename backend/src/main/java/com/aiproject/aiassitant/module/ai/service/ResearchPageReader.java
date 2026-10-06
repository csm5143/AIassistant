package com.aiproject.aiassitant.module.ai.service;

import com.aiproject.aiassitant.module.knowledge.service.PublicHttpUrl;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;

/** Lightweight reader: bounded HTML body, no browser, no extra model call. */
@Component
public class ResearchPageReader {
    public record Page(String url, String title, String text) {}
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    public Page read(String url) throws Exception {
        String current = url;
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        for (int redirects = 0; redirects <= 3; redirects++) {
            PublicHttpUrl.validate(current);
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new java.io.IOException("网页读取超时");
            var request = HttpRequest.newBuilder(URI.create(current)).timeout(Duration.ofNanos(remaining))
                    .header("User-Agent", "AIassistant-SourceReader/1.0")
                    .header("Accept", "text/html,text/plain").GET().build();
            // ofByteArray completes under the request timeout; a bounded subscriber rejects oversized pages.
            var response = client.send(request, info -> HttpResponse.BodySubscribers.mapping(
                    new LimitedBodySubscriber(2_000_000), bytes -> new String(bytes, java.nio.charset.StandardCharsets.UTF_8)));
            if (response.statusCode() >= 300 && response.statusCode() < 400) {
                String location = response.headers().firstValue("location").orElseThrow();
                current = URI.create(current).resolve(location).toString();
                continue;
            }
            if (response.statusCode() != 200) throw new java.io.IOException("网页状态 " + response.statusCode());
            String type = response.headers().firstValue("content-type").orElse("").toLowerCase();
            if (!type.contains("text/html") && !type.contains("text/plain")) throw new java.io.IOException("非文本网页");
            var doc = Jsoup.parse(response.body(), current);
            String title = doc.title();
            doc.select("script,style,nav,footer,header,aside,noscript,form").remove();
            var main = doc.selectFirst("main,article,[role=main],.sect1,.document,.content");
            var body = main == null ? doc.body() : main;
            // Keep table rows and paragraph boundaries instead of concatenating all cells.
            for(var row : body.select("tr")) row.text(String.join(" | ",row.select("th,td").eachText()) + "\n");
            String text = body.wholeText().replaceAll("[\\t \\x0B\\f]+"," ").replaceAll("(?m)^ +$", "").replaceAll("\\n{3,}","\n\n").trim();
            if (text.length() < 80) throw new java.io.IOException("网页正文不足");
            return new Page(current, title, text.substring(0, Math.min(text.length(), 24000)));
        }
        throw new java.io.IOException("网页重定向过多");
    }

    static class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
        private final java.util.concurrent.CompletableFuture<byte[]> result = new java.util.concurrent.CompletableFuture<>();
        private final int limit;
        private java.util.concurrent.Flow.Subscription subscription;
        LimitedBodySubscriber(int limit) { this.limit = limit; }
        public java.util.concurrent.CompletionStage<byte[]> getBody() { return result; }
        public void onSubscribe(java.util.concurrent.Flow.Subscription s) { subscription = s; s.request(1); }
        public void onNext(java.util.List<java.nio.ByteBuffer> buffers) {
            for (var buffer : buffers) {
                if (body.size() + buffer.remaining() > limit) { subscription.cancel(); result.completeExceptionally(new java.io.IOException("网页过大")); return; }
                byte[] bytes = new byte[buffer.remaining()]; buffer.get(bytes); body.writeBytes(bytes);
            }
            subscription.request(1);
        }
        public void onError(Throwable t) { result.completeExceptionally(t); }
        public void onComplete() { result.complete(body.toByteArray()); }
    }
}
