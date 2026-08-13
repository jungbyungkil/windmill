package com.windmill.exception;

import com.windmill.dto.DuplicateItineraryResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(DuplicateActiveItineraryException.class)
    public ResponseEntity<DuplicateItineraryResponse> handleDuplicateActiveItinerary(
            DuplicateActiveItineraryException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(DuplicateItineraryResponse.from(e.getExisting()));
    }
}
