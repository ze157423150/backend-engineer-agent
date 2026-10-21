"""Dependency-free BM25 baseline over tool names, descriptions and parameters."""
import math
from collections import Counter
from common import terms, query_text


def rank(state, catalog):
    documents = []
    for tool in catalog:
        function = tool["function"]
        text = function["name"] + " " + function.get("description", "")
        for key, value in function.get("parameters", {}).get("properties", {}).items():
            text += " " + key + " " + value.get("description", "")
        documents.append((function["name"], Counter(terms(text))))
    count = len(documents)
    if not count:
        return []
    average = sum(sum(doc.values()) for _, doc in documents) / count or 1
    frequencies = Counter(word for _, doc in documents for word in doc)
    query = Counter(terms(query_text(state)))
    scores = []
    for name, document in documents:
        length = sum(document.values())
        score = 0.0
        for term, occurrences in query.items():
            frequency = document[term]
            if not frequency:
                continue
            inverse = math.log(1 + (count - frequencies[term] + 0.5) / (frequencies[term] + 0.5))
            normalization = frequency + 1.2 * (1 - 0.75 + 0.75 * length / average)
            score += min(occurrences, 3) * inverse * frequency * 2.2 / normalization
        scores.append((name, score))
    # Keep zero-score candidates as last-resort fillers; do not learn thresholds
    # from the evaluation set. Report fixed k so recall can be compared fairly.
    return [name for name, score in sorted(scores, key=lambda pair: (-pair[1], pair[0]))]


def select(state, catalog, k):
    return rank(state, catalog)[:k]
