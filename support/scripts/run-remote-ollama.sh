#!/bin/bash

export OLLAMA_BASE_URL=https://w15lrx35-11434.use.devtunnels.ms
export OLLAMA_MODEL="gpt-oss:20b"

mvn spring-boot:run -Dspring-boot.run.arguments="--openworker.models.default-provider=ollama --openworker.models.default-model=gpt-oss:20b"
