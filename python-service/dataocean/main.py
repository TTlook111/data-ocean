from fastapi import APIRouter, FastAPI

from dataocean.knowledge.router import router as knowledge_router

app = FastAPI(title="DataOcean AI Service", version="0.1.0")
internal_router = APIRouter(prefix="/internal")


@app.get("/health")
async def health() -> dict[str, str]:
    return {"status": "ok"}


@internal_router.get("/health")
async def internal_health() -> dict[str, str]:
    return {"status": "ok"}


@internal_router.delete("/sql/pools/{datasource_id}")
async def destroy_sql_pool(datasource_id: int) -> dict[str, str | int]:
    # Actual SQLAlchemy pool management lands with the NL2SQL execution module.
    return {"status": "ok", "datasourceId": datasource_id}


app.include_router(internal_router)
app.include_router(knowledge_router)
