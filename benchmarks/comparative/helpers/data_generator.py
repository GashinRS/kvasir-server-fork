"""
Test data generation for comparative benchmarks.

Generates files of various sizes and types for Scenario 1 (File I/O),
and SAREF RDF data for Scenario 2 (RDF Query).
"""

from __future__ import annotations
import io
import os
import random
import uuid
from dataclasses import dataclass
from datetime import datetime, timezone, timedelta

# --- Scenario 1: File categories ---


@dataclass
class FileCategory:
    name: str
    size_bytes: int
    count: int
    content_type: str
    extension: str


FILE_CATEGORIES: list[FileCategory] = [
    FileCategory("small_25kb", 25 * 1024, 100, "text/plain", ".txt"),
    FileCategory("medium_250kb", 250 * 1024, 50, "text/plain", ".txt"),
    FileCategory("large_2500kb", 2500 * 1024, 20, "text/plain", ".txt"),
    FileCategory("xl_25mb", 25 * 1024 * 1024, 5, "application/octet-stream", ".bin"),
    FileCategory("rdf_500kb", 500 * 1024, 30, "application/n-triples", ".nt"),
    FileCategory("binary_1mb", 1024 * 1024, 20, "application/octet-stream", ".bin"),
]


def generate_file_content(category: FileCategory, index: int) -> bytes:
    """Generate deterministic file content for a given category and index."""
    rng = random.Random(f"{category.name}-{index}")
    if category.content_type == "application/n-triples":
        return _generate_rdf_content(category.size_bytes, rng)
    elif category.content_type == "text/plain":
        return _generate_text_content(category.size_bytes, rng)
    else:
        return _generate_binary_content(category.size_bytes, rng)


def _generate_text_content(size: int, rng: random.Random) -> bytes:
    """Generate pseudo-random text content of approximately the given size."""
    words = [
        "the",
        "quick",
        "brown",
        "fox",
        "jumps",
        "over",
        "lazy",
        "dog",
        "benchmark",
        "performance",
        "data",
        "storage",
        "server",
        "test",
        "solid",
        "kvasir",
        "linked",
        "resource",
        "protocol",
        "web",
    ]
    buf = io.StringIO()
    while buf.tell() < size:
        line_words = [rng.choice(words) for _ in range(rng.randint(5, 15))]
        buf.write(" ".join(line_words) + "\n")
    return buf.getvalue()[:size].encode("utf-8")


def _generate_binary_content(size: int, rng: random.Random) -> bytes:
    """Generate pseudo-random binary content."""
    return bytes(rng.getrandbits(8) for _ in range(size))


def _generate_rdf_content(size: int, rng: random.Random) -> bytes:
    """Generate N-Triples RDF content of approximately the given size."""
    buf = io.StringIO()
    i = 0
    while buf.tell() < size:
        subj = f"<http://example.org/resource/{uuid.UUID(int=rng.getrandbits(128))}>"
        buf.write(
            f"{subj} <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://example.org/Thing> .\n"
        )
        buf.write(
            f'{subj} <http://www.w3.org/2000/01/rdf-schema#label> "Resource {i}" .\n'
        )
        buf.write(
            f'{subj} <http://example.org/value> "{rng.randint(0, 10000)}"^^<http://www.w3.org/2001/XMLSchema#integer> .\n'
        )
        i += 1
    return buf.getvalue()[:size].encode("utf-8")


def file_key(category: FileCategory, index: int) -> str:
    """Generate a storage key/path for a file."""
    return f"bench/{category.name}/file_{index:04d}{category.extension}"


# --- Scenario 2: SAREF data ---

SAREF_BASE = "https://saref.etsi.org/core/"
EXAMPLE_BASE = "http://example.org/"
RDF_TYPE = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type"
RDFS_LABEL = "http://www.w3.org/2000/01/rdf-schema#label"
XSD_DATETIME = "http://www.w3.org/2001/XMLSchema#dateTime"
XSD_INTEGER = "http://www.w3.org/2001/XMLSchema#integer"

JSONLD_CONTEXT = {
    "kss": "https://kvasir.discover.ilabt.imec.be/vocab#",
    "ex": EXAMPLE_BASE,
    "saref": SAREF_BASE,
    "rdfs": "http://www.w3.org/2000/01/rdf-schema#",
}


@dataclass
class SarefDataset:
    """Holds generated SAREF sensor + measurement data."""

    sensor_ids: list[str]
    nr_sensors: int
    records_per_sensor: int
    start_timestamp: datetime
    ntriples_bytes: bytes  # Full dataset as N-Triples


def generate_saref_dataset(
    nr_sensors: int = 5,
    records_per_sensor: int = 2000,
) -> SarefDataset:
    """Generate a SAREF sensor + measurement dataset as N-Triples."""
    start_ts = datetime.now(tz=timezone.utc).replace(microsecond=0) - timedelta(
        seconds=records_per_sensor
    )
    sensor_ids = [f"{EXAMPLE_BASE}sensor-{uuid.uuid4()}" for _ in range(nr_sensors)]
    rng = random.Random("saref-benchmark")

    buf = io.StringIO()
    # Sensor triples
    for i, sid in enumerate(sensor_ids):
        s = f"<{sid}>"
        buf.write(f"{s} <{RDF_TYPE}> <{SAREF_BASE}Sensor> .\n")
        buf.write(f'{s} <{RDFS_LABEL}> "Benchmark sensor {i}" .\n')

    # Measurement triples
    for sid in sensor_ids:
        for j in range(records_per_sensor):
            ts = start_ts + timedelta(seconds=j)
            obs_id = f"{sid}_Observation{j}"
            o = f"<{obs_id}>"
            ts_str = ts.strftime("%Y-%m-%dT%H:%M:%S.%f") + "Z"
            buf.write(f"{o} <{RDF_TYPE}> <{SAREF_BASE}Measurement> .\n")
            buf.write(f"{o} <{SAREF_BASE}measurementMadeBy> <{sid}> .\n")
            buf.write(
                f'{o} <{SAREF_BASE}hasTimestamp> "{ts_str}"^^<{XSD_DATETIME}> .\n'
            )
            buf.write(
                f'{o} <{SAREF_BASE}hasValue> "{rng.randint(35, 39)}"^^<{XSD_INTEGER}> .\n'
            )

    nt_bytes = buf.getvalue().encode("utf-8")
    return SarefDataset(
        sensor_ids=sensor_ids,
        nr_sensors=nr_sensors,
        records_per_sensor=records_per_sensor,
        start_timestamp=start_ts,
        ntriples_bytes=nt_bytes,
    )
