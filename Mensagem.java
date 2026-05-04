import java.io.Serializable;

public class Mensagem implements Serializable {
    private static final long serialVersionUID = 1L;

    public enum TipoAcao {
        IDENTIFICAR, // <-- Ação para registrar o nome do usuário
        CRIAR_TOPICO,
        SUBSCRIBE,
        PUBLISH,
        UNSUBSCRIBE
    }

    private TipoAcao acao;
    private String topico;
    private String payload;
    private String remetente; // <-- Aqui vai o nome escolhido pelo cliente

    public Mensagem(TipoAcao acao, String topico, String payload, String remetente) {
        this.acao = acao;
        this.topico = topico;
        this.payload = payload;
        this.remetente = remetente;
    }

    public TipoAcao getAcao() { return acao; }
    public String getTopico() { return topico; }
    public String getPayload() { return payload; }
    public String getRemetente() { return remetente; }
}