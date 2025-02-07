FROM registry.redhat.io/ubi9/nodejs-20 AS build
WORKDIR /app
COPY package.json package-lock.json* ./
USER root
RUN npm install
ENV NODE_OPTIONS="--openssl-legacy-provider"
COPY . .
RUN npm run build

FROM registry.redhat.io/ubi9/nodejs-20 AS production
WORKDIR /app
USER root
COPY --from=build /app /app
COPY src/* /app/
COPY public/* /app/
RUN chown -R 1001:1001 /app /app/node_modules
EXPOSE 3000
USER 1001
CMD ["npm", "start"]