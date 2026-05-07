package io.agentscope.demo.app.web;

import io.agentscope.demo.app.service.FileJobService;
import io.agentscope.demo.app.web.dto.FileAnalyzeResponse;
import io.agentscope.demo.app.web.dto.FileJobStatusResponse;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/files")
public class FileController {

    private final FileJobService fileJobService;

    public FileController(FileJobService fileJobService) {
        this.fileJobService = fileJobService;
    }

    @PostMapping(value = "/analyze", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public FileAnalyzeResponse analyze(@RequestPart("file") MultipartFile file) throws IOException {
        return fileJobService.start(file);
    }

    @GetMapping("/{jobId}")
    public FileJobStatusResponse status(@PathVariable String jobId) {
        return fileJobService.status(jobId);
    }
}
