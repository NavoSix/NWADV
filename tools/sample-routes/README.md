# Sample route generator

The bundled sample GPX files in `assets/routes/` were generated with these scripts: a least-cost
path over real elevation data (AWS Terrarium tiles), penalising steep grades so the line follows
valleys and passes the way roads do. They are approximations for trying the game; ride your own
club GPX files for the real thing.

    python3 -m venv venv && ./venv/bin/pip install numpy scikit-image pillow
    ./venv/bin/python make_samples.py ../../assets/routes
    ./venv/bin/python make_c6.py ../../assets/routes
