package ITerview.iterview.Service;

import ITerview.iterview.Domain.auth.Authority;
import ITerview.iterview.Domain.auth.Member;
import ITerview.iterview.Domain.auth.MemberAuth;
import ITerview.iterview.Domain.jwt.RefreshToken;
import ITerview.iterview.Dto.jwt.TokenDTO;
import ITerview.iterview.Dto.jwt.TokenReqDTO;
import ITerview.iterview.Dto.login.LoginReqDTO;
import ITerview.iterview.Dto.member.MemberReqDTO;
import ITerview.iterview.Dto.member.MemberRespDTO;
import ITerview.iterview.ExceptionHandler.AuthorityExceptionType;
import ITerview.iterview.ExceptionHandler.BizException;
import ITerview.iterview.ExceptionHandler.JwtExceptionType;
import ITerview.iterview.ExceptionHandler.MemberExceptionType;
import ITerview.iterview.Jwt.CustomEmailPasswordAuthToken;
import ITerview.iterview.Jwt.TokenProvider;
import ITerview.iterview.Repository.AuthorityRepository;
import ITerview.iterview.Repository.MemberRepository;
import ITerview.iterview.Repository.RefreshTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.HashSet;
import java.util.Set;


@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {
    private final AuthenticationManager authenticationManager;
    private final MemberRepository memberRepository;
    private final AuthorityRepository authorityRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenProvider tokenProvider;
    private final RefreshTokenRepository refreshTokenRepository;
    private final CustomUserDetailsService customUserDetailsService;

    public static final String BEARER_PREFIX = "Bearer ";

    private String resolveToken(String bearerToken) {
        // bearer : 123123123123123 -> return 123123123123123123
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith(BEARER_PREFIX)) {
            return bearerToken.substring(7);
        }
        return null;
    }

    @Transactional
    public MemberRespDTO signup(MemberReqDTO memberRequestDto) {
        if (memberRepository.existsByEmail(memberRequestDto.getEmail())) {
            throw new BizException(MemberExceptionType.DUPLICATE_USER);
        }

        // DB 에서 ROLE_USER를 찾아서 권한으로 추가한다.
        Authority authority = authorityRepository
                .findByAuthorityName(MemberAuth.ROLE_USER).orElseThrow(()->new BizException(AuthorityExceptionType.NOT_FOUND_AUTHORITY));

        Set<Authority> set = new HashSet<>();
        set.add(authority);


        Member member = memberRequestDto.toMember(passwordEncoder, set);
        log.debug("member = {}",member);
        return MemberRespDTO.of(memberRepository.saveMember(member));
    }

    @Transactional
    public TokenDTO login(LoginReqDTO loginReqDTO) {
        CustomEmailPasswordAuthToken customEmailPasswordAuthToken = new CustomEmailPasswordAuthToken(loginReqDTO.getEmail(), loginReqDTO.getPassword());
        Authentication authenticate = authenticationManager.authenticate(customEmailPasswordAuthToken);
        String email = authenticate.getName();
        Member member = customUserDetailsService.getMember(email);

        String accessToken = tokenProvider.createAccessToken(email, member.getAuthorities());
        // 만약 해당 유저의 refreshToken이 이미 있다면 삭제하고 재생성
        if (refreshTokenRepository.existsByKey(email)){
            refreshTokenRepository.deleteRefreshToken(refreshTokenRepository.findByKey(email).orElseThrow(()->new BizException(MemberExceptionType.NOT_FOUND_USER)));
        }
        String newRefreshToken = tokenProvider.createRefreshToken(email, member.getAuthorities());

        //refresh Token 저장
        refreshTokenRepository.saveRefreshToken(
                RefreshToken.builder()
                        .key(email)
                        .value(newRefreshToken)
                        .build()
        );

        return tokenProvider.createTokenDTO(accessToken, newRefreshToken);

    }

    @Transactional
    public TokenDTO reissue(TokenReqDTO tokenRequestDto) {
        /*
         *  accessToken 은 JWT Filter 에서 검증되고 옴
         * */
        String originAccessToken = tokenRequestDto.getAccessToken();
        String originRefreshToken = tokenRequestDto.getRefreshToken();

        // refreshToken 검증
        int refreshTokenFlag = tokenProvider.validateToken(originRefreshToken);

        log.debug("refreshTokenFlag = {}", refreshTokenFlag);

        //refreshToken 검증하고 상황에 맞는 오류를 내보낸다.
        if (refreshTokenFlag == -1) {
            throw new BizException(JwtExceptionType.BAD_TOKEN); // 잘못된 리프레시 토큰
        } else if (refreshTokenFlag == 2) {
            throw new BizException(JwtExceptionType.REFRESH_TOKEN_EXPIRED); // 유효기간 끝난 토큰
        }

        // 2. Access Token 에서 Member Email 가져오기
        Authentication authentication = tokenProvider.getAuthentication(originAccessToken);

        log.debug("Authentication = {}",authentication);

        // 3. 저장소에서 Member Email 를 기반으로 Refresh Token 값 가져옴
        RefreshToken refreshToken = refreshTokenRepository.findByKey(authentication.getName())
                .orElseThrow(() -> new BizException(MemberExceptionType.LOGOUT_MEMBER)); // 로그 아웃된 사용자


        // 4. Refresh Token 일치하는지 검사
        if (!refreshToken.getValue().equals(originRefreshToken)) {
            throw new BizException(JwtExceptionType.BAD_TOKEN); // 토큰이 일치하지 않습니다.
        }

        // 5. 새로운 토큰 생성
        String email = tokenProvider.getMemberEmailByToken(originAccessToken);
        Member member = customUserDetailsService.getMember(email);

        String newAccessToken = tokenProvider.createAccessToken(email, member.getAuthorities());
        String newRefreshToken = tokenProvider.createRefreshToken(email, member.getAuthorities());
        TokenDTO tokenDto = tokenProvider.createTokenDTO(newAccessToken, newRefreshToken);

        log.debug("refresh Origin = {}",originRefreshToken);
        log.debug("refresh New = {} ",newRefreshToken);
        // 6. 저장소 정보 업데이트 (dirtyChecking으로 업데이트)
        refreshToken.updateValue(newRefreshToken);

        // 토큰 발급
        return tokenDto;
    }

    public ResponseEntity logout(String bearerToken){
        String accessToken = resolveToken(bearerToken);
        String email = tokenProvider.getMemberEmailByToken(accessToken);
        RefreshToken refreshToken = refreshTokenRepository.findByKey(email)
                .orElseThrow(() -> new BizException(MemberExceptionType.LOGOUT_MEMBER));

        refreshTokenRepository.deleteRefreshToken(refreshToken);
        return new ResponseEntity(HttpStatus.OK);
    }

    public MemberRespDTO getInfo(String bearerToken){
        String accessToken = resolveToken(bearerToken);
        String email = tokenProvider.getMemberEmailByToken(accessToken);
        Member member = memberRepository.findByEmail(email)
                .orElseThrow(() -> new BizException(MemberExceptionType.NOT_FOUND_USER));

        return MemberRespDTO.of(member);
    }
}
