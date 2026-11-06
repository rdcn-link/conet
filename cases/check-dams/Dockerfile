FROM xstream/operator-python:1.0
USER 0
COPY runtime/wheels /wheels
COPY runtime/requirements-lock.txt /tmp/requirements.txt
RUN ["python", "-m", "pip", "install", "--break-system-packages", "--no-index", "--find-links=/wheels", "--no-deps", "--target=/opt/geo", "-r", "/tmp/requirements.txt"]
ENV PYTHONPATH=/opt/geo:/opt/xstream-sdk:/opt/python/lib/python3.12/site-packages
ENV YOLO_CONFIG_DIR=/tmp/yolo MPLCONFIGDIR=/tmp/matplotlib OMP_NUM_THREADS=2
