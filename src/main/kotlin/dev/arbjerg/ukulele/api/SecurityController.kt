package dev.arbjerg.ukulele.api

import org.springframework.web.bind.annotation.CrossOrigin
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/security")
@CrossOrigin(origins = ["*"])
class SecurityController(val securityService: SecurityService) {
    @GetMapping("/stats")
    fun getStats(): Map<String, Long> {
        return mapOf("unauthorizedAttempts" to securityService.getUnauthorizedCount())
    }

    @org.springframework.web.bind.annotation.PostMapping("/reset")
    fun resetStats() {
        securityService.resetUnauthorized()
    }
}
