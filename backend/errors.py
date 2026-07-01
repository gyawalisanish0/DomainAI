"""Structured error codes surfaced to the Android client."""
from enum import Enum

from fastapi import HTTPException


class ErrorCode(str, Enum):
    NO_TOKEN = "no_token"
    INVALID_TOKEN = "invalid_token"
    NO_MODEL_LOADED = "no_model_loaded"
    MODEL_LOAD_FAILED = "model_load_failed"
    INFERENCE_FAILED = "inference_failed"
    BAD_REQUEST = "bad_request"


def api_error(status: int, code: ErrorCode, detail: str) -> HTTPException:
    return HTTPException(
        status_code=status,
        detail={"code": code.value, "message": detail},
    )
