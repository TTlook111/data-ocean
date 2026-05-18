from fastapi import APIRouter, FastAPI


app = FastAPI(title="DataOcean AI Service", version="0.1.0")
internal_router = APIRouter(prefix="/internal")


@app.get("/health")
async def health() -> dict[str, str]:
    return {"status": "ok"}


@internal_router.get("/health")
async def internal_health() -> dict[str, str]:
    return {"status": "ok"}


app.include_router(internal_router)
