FROM node:22.23.3-alpine@sha256:0a7108bf6c7bf5de370ffb1a3ed6be93d405b43ff159f681a8d18c0e2bc2e402 AS build
WORKDIR /build
COPY package.json package-lock.json .npmrc ./
RUN npm ci
COPY angular.json tsconfig*.json ./
COPY src ./src
COPY public ./public
RUN npm run build:dev
FROM node:22.23.3-alpine@sha256:0a7108bf6c7bf5de370ffb1a3ed6be93d405b43ff159f681a8d18c0e2bc2e402 AS runtime
ENV NODE_ENV=production PORT=10000 PORTAL_ROOT=/app/browser ADMIN_BACKEND_ORIGIN=https://simertpi-backend-dev.onrender.com
WORKDIR /app
COPY --from=build --chown=node:node /build/dist/simertpi-admin-web/browser ./browser
COPY --chown=node:node deploy/server.mjs ./server.mjs
USER node
EXPOSE 10000
HEALTHCHECK --interval=30s --timeout=3s --start-period=10s CMD node -e "fetch('http://127.0.0.1:'+process.env.PORT+'/healthz').then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(1))"
CMD ["node","/app/server.mjs"]
