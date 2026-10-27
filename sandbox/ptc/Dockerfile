FROM python:3.12-slim
COPY sandbox/ptc/runtime.py /opt/ptc/runtime.py
WORKDIR /tmp
USER 65534:65534
ENTRYPOINT ["python3", "-I", "-u", "/opt/ptc/runtime.py"]
