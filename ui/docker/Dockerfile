FROM gitlab.ilabt.imec.be:4567/obelisk/packages/desmart/nginx-spa:2.0.0
# Source from desmart/nginx-spa

COPY dist/kvasir-ui/browser /app/_ui
COPY docker/default.conf /etc/nginx/conf.d/default.conf
COPY docker/setup-host.sh /root/setup-host.sh
RUN apk update
RUN apk add dos2unix
RUN apk add jq
RUN dos2unix /root/setup-host.sh
RUN chmod +x /root/setup-host.sh

EXPOSE 80

ENTRYPOINT ["./root/setup-host.sh"]
STOPSIGNAL SIGTERM
CMD ["nginx", "-g", "daemon off;"]
