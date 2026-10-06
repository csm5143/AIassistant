FROM node:22-alpine AS build
WORKDIR /build
COPY user-frontend/package*.json ./
RUN --mount=type=cache,target=/root/.npm npm ci
COPY user-frontend/ ./
RUN npm run build

FROM nginx:1.28-alpine
COPY deployment/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /build/dist /usr/share/nginx/html
EXPOSE 80
