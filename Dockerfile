FROM node:22-bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg ca-certificates && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY . /app
ENV NODE_ENV=production PORT=8090
EXPOSE 8090
USER node
CMD ["node", "server/server.js"]
