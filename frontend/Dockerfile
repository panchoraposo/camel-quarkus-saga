FROM registry.redhat.io/ubi10/nodejs-22-minimal:10.0 AS build
WORKDIR /app
COPY package.json package-lock.json* ./
USER root
RUN npm install
COPY . .
RUN npm run build

FROM registry.redhat.io/ubi10/nodejs-22-minimal:10.0 AS production
WORKDIR /app
USER root
COPY --from=build /app /app
RUN chgrp -R 0 /app && \
    chmod -R g=u /app
EXPOSE 3000
USER 1001
CMD ["npm", "start"]