ARG NGINX_IMAGE=nginx:latest
FROM ${NGINX_IMAGE}
COPY nginx.conf /etc/nginx/nginx.conf
COPY dist/ /usr/share/nginx/html/
USER 10001:10001
EXPOSE 8081
ENTRYPOINT ["nginx"]
CMD ["-g", "daemon off;"]
