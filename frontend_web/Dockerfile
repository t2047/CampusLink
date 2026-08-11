# ─── Frontend 多阶段构建：vite build → nginx 静态托管 ───
# 反代 /api 到后端 chat-backend:8080（同源部署，前端无需 VITE_API_BASE）

FROM node:22-alpine AS build
WORKDIR /app
COPY frontend_web/package*.json ./
RUN npm ci
COPY frontend_web/ ./
RUN npm run build

FROM nginx:1.27-alpine
# nginx 官方镜像：/etc/nginx/templates/*.template 启动时自动 envsubst（替换 ${CERT_DOMAIN}）
ENV CERT_DOMAIN=campuslink.local
COPY frontend_web/nginx.conf /etc/nginx/templates/default.conf.template
COPY --from=build /app/dist /usr/share/nginx/html
# 证书缺失时生成自签占位（443 证书就绪前 nginx 也能启动；certbot 签发后覆盖）
COPY frontend_web/ensure-cert.sh /docker-entrypoint.d/40-ensure-cert.sh
# nginx:alpine 不带 openssl CLI，需安装用于生成占位证书
RUN apk add --no-cache openssl && chmod +x /docker-entrypoint.d/40-ensure-cert.sh
EXPOSE 80 443
