FROM eclipse-temurin:25-jre-jammy
COPY runtime/bin/ollama /usr/local/bin/ollama
COPY runtime/lib/ollama/ /usr/local/lib/ollama/
ENV OLLAMA_HOST=0.0.0.0:11434 \
    OLLAMA_MODELS=/models \
    OLLAMA_KEEP_ALIVE=-1 \
    OLLAMA_NUM_PARALLEL=1 \
    OLLAMA_MAX_LOADED_MODELS=1 \
    OLLAMA_CONTEXT_LENGTH=8192 \
    OLLAMA_NO_CLOUD=1 \
    OLLAMA_NOPRUNE=1 \
    HOME=/tmp
USER 10001:10001
EXPOSE 11434
ENTRYPOINT ["/usr/local/bin/ollama"]
CMD ["serve"]
