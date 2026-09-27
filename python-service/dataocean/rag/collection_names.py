"""Names for build-isolated RAG collections."""


def build_collection_name(datasource_id: int, build_id: str) -> str:
    normalized_build_id = str(build_id).replace("-", "").lower()
    return f"dataocean_rag_ds{datasource_id}_b{normalized_build_id}"


def is_build_collection_name(datasource_id: int, build_id: str, collection_name: str | None) -> bool:
    return collection_name == build_collection_name(datasource_id, build_id)
