# 빌드 단계: Vite로 정적 파일 생성
FROM node:24-alpine AS build
WORKDIR /src
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
# 브라우저가 호출할 Gateway 주소 (빌드 시점에 번들에 들어감)
ARG VITE_GATEWAY_URL=http://localhost:8080
ARG VITE_IAM_DOMAIN=pmart.test
ENV VITE_GATEWAY_URL=$VITE_GATEWAY_URL VITE_IAM_DOMAIN=$VITE_IAM_DOMAIN
RUN npm run build

# 실행 단계: Nginx로 정적 파일 제공
FROM nginx:1.30.6-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /src/dist /usr/share/nginx/html
EXPOSE 80
