FROM otel/opentelemetry-collector-contrib:0.120.0

COPY config.yaml /etc/otelcol-contrib/config.yaml

ENV PORT=8080
EXPOSE 8080

ENTRYPOINT ["/otelcol-contrib"]
CMD ["--config=/etc/otelcol-contrib/config.yaml"]
