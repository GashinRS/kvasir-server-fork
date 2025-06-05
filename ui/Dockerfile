FROM node:20-alpine

WORKDIR /usr/src/app

COPY . /usr/src/app

RUN npm i -g @angular/cli
RUN npm ci

CMD ["ng", "serve", "--host", "0.0.0.0"]
