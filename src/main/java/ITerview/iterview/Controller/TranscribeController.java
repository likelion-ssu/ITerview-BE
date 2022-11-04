package ITerview.iterview.Controller;

import ITerview.iterview.Dto.transcription.TranscriptionCreateAPIRequestDTO;
import ITerview.iterview.Dto.transcription.TranscriptionAPIRequestDTO;
import ITerview.iterview.Dto.transcription.TranscriptionAPIResponseDTO;
import ITerview.iterview.Service.TranscriptionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class TranscribeController {
    private final TranscriptionService transcriptionService;

    @PostMapping("/transcription/create")
    public ResponseEntity createTranscription(@RequestBody TranscriptionCreateAPIRequestDTO transcriptionCreateAPIRequestDTO){
        return transcriptionService.createTranscription(transcriptionCreateAPIRequestDTO);
    }

    @PostMapping("/transcription")
    public TranscriptionAPIResponseDTO getTranscription(@RequestBody TranscriptionAPIRequestDTO transcriptionAPIRequestDTO){
        return transcriptionService.getVideoTranscription(transcriptionAPIRequestDTO);
    }

}
