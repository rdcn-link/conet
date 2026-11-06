FROM xstream/operator-python:1.0
USER 0
COPY runtime/wheels /wheels
COPY runtime/requirements-lock.txt /tmp/requirements.txt
RUN ["python", "-m", "pip", "install", "--break-system-packages", "--no-index", "--find-links=/wheels", "--no-deps", "--target=/opt/astro", "-r", "/tmp/requirements.txt"]
ENV PYTHONPATH=/opt/astro:/opt/xstream-sdk:/opt/python/lib/python3.12/site-packages
