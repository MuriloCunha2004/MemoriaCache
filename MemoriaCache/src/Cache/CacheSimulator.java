package Cache;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Random;

public final class CacheSimulator {
    private enum PoliticaEscrita { WRITE_THROUGH, WRITE_BACK }
    private enum PoliticaSubstituicao { LRU, RANDOM }

    private static final class LinhaCache {
        boolean valida;
        boolean dirty;
        long tag;
        long ultimoUso;
    }

    private final PoliticaEscrita politicaEscrita;
    private final PoliticaSubstituicao politicaSubstituicao;
    private final int tamanhoLinha;
    private final int numeroLinhas;
    private final int associatividade;
    private final int numeroConjuntos;
    private final double hitTime;
    private final double tempoLeituraMemoria;
    private final double tempoEscritaMemoria;
    private final LinhaCache[][] conjuntos;
    private final Random random = new Random(42);

    private long relogio;
    private long leituras;
    private long escritas;
    private long hitsLeitura;
    private long hitsEscrita;
    private long leiturasMemoria;
    private long escritasMemoria;

    private CacheSimulator(PoliticaEscrita politicaEscrita, int tamanhoLinha, int numeroLinhas,
            int associatividade, double hitTime, PoliticaSubstituicao politicaSubstituicao,
            double tempoLeituraMemoria, double tempoEscritaMemoria) {
        this.politicaEscrita = politicaEscrita;
        this.tamanhoLinha = tamanhoLinha;
        this.numeroLinhas = numeroLinhas;
        this.associatividade = associatividade;
        this.numeroConjuntos = numeroLinhas / associatividade;
        this.hitTime = hitTime;
        this.politicaSubstituicao = politicaSubstituicao;
        this.tempoLeituraMemoria = tempoLeituraMemoria;
        this.tempoEscritaMemoria = tempoEscritaMemoria;
        this.conjuntos = new LinhaCache[numeroConjuntos][associatividade];
        for (int s = 0; s < numeroConjuntos; s++) {
            for (int w = 0; w < associatividade; w++) {
                conjuntos[s][w] = new LinhaCache();
            }
        }
    }

    private void access(long endereco, char operacao) {
        relogio++;

        long numeroBloco = Long.divideUnsigned(endereco, tamanhoLinha);
        int indiceConjunto = (int) Long.remainderUnsigned(numeroBloco, numeroConjuntos);
        long tag = Long.divideUnsigned(numeroBloco, numeroConjuntos);
        LinhaCache linhaEncontrada = buscarLinha(indiceConjunto, tag);

        if (operacao == 'R') {
            leituras++;
            if (linhaEncontrada != null) {
                hitsLeitura++;
                linhaEncontrada.ultimoUso = relogio;
            } else {
                LinhaCache linha = alocarLinha(indiceConjunto, tag);
                linha.ultimoUso = relogio;
                lerDaMemoria();
            }
        } else if (operacao == 'W') {
            escritas++;
            if (linhaEncontrada != null) {
                hitsEscrita++;
                linhaEncontrada.ultimoUso = relogio;
                if (politicaEscrita == PoliticaEscrita.WRITE_BACK) {
                    linhaEncontrada.dirty = true;
                } else {
                    escreverNaMemoria();
                }
            } else if (politicaEscrita == PoliticaEscrita.WRITE_BACK) {
                LinhaCache linha = alocarLinha(indiceConjunto, tag);
                lerDaMemoria();
                linha.dirty = true;
                linha.ultimoUso = relogio;
            } else {
                escreverNaMemoria();
            }
        } else {
            throw new IllegalArgumentException("Operacao invalida: " + operacao);
        }
    }

    private LinhaCache buscarLinha(int indiceConjunto, long tag) {
        for (LinhaCache linha : conjuntos[indiceConjunto]) {
            if (linha.valida && linha.tag == tag) return linha;
        }
        return null;
    }

    private LinhaCache alocarLinha(int indiceConjunto, long tag) {
        LinhaCache vitima = null;
        for (LinhaCache linha : conjuntos[indiceConjunto]) {
            if (!linha.valida) {
                vitima = linha;
                break;
            }
        }
        if (vitima == null) {
            vitima = escolherVitima(indiceConjunto);
            if (vitima.dirty) escreverNaMemoria();
        }
        vitima.valida = true;
        vitima.dirty = false;
        vitima.tag = tag;
        return vitima;
    }

    private LinhaCache escolherVitima(int indiceConjunto) {
        if (politicaSubstituicao == PoliticaSubstituicao.RANDOM) {
            return conjuntos[indiceConjunto][random.nextInt(associatividade)];
        }
        LinhaCache maisAntiga = conjuntos[indiceConjunto][0];
        for (int i = 1; i < associatividade; i++) {
            if (conjuntos[indiceConjunto][i].ultimoUso < maisAntiga.ultimoUso) maisAntiga = conjuntos[indiceConjunto][i];
        }
        return maisAntiga;
    }

    private void lerDaMemoria() {
        leiturasMemoria++;
    }

    private void escreverNaMemoria() {
        escritasMemoria++;
    }

    private void flush() {
        if (politicaEscrita != PoliticaEscrita.WRITE_BACK) return;
        for (LinhaCache[] conjunto : conjuntos) {
            for (LinhaCache linha : conjunto) {
                if (linha.valida && linha.dirty) {
                    escreverNaMemoria();
                    linha.dirty = false;
                }
            }
        }
    }

    private void simular(Path arquivoAcessos) throws IOException {
        try (BufferedReader leitor = Files.newBufferedReader(arquivoAcessos, StandardCharsets.UTF_8)) {
            String texto;
            int numeroLinha = 0;
            while ((texto = leitor.readLine()) != null) {
                numeroLinha++;
                texto = texto.trim();
                if (texto.isEmpty()) continue;
                String[] partes = texto.split("\\s+");
                if (partes.length != 2 || partes[1].length() != 1) {
                    throw new IllegalArgumentException("Linha " + numeroLinha + " invalida: " + texto);
                }
                final long endereco;
                try {
                    endereco = Long.parseUnsignedLong(partes[0], 16);
                } catch (NumberFormatException erro) {
                    throw new IllegalArgumentException("Endereco invalido na linha " + numeroLinha + ": " + partes[0]);
                }
                if (Long.compareUnsigned(endereco, 0xFFFF_FFFFL) > 0) {
                    throw new IllegalArgumentException("Endereco fora de 32 bits na linha " + numeroLinha);
                }
                access(endereco, Character.toUpperCase(partes[1].charAt(0)));
            }
        }
        flush();
    }

    private static double taxaHit(long hits, long acessos) {
        return acessos == 0 ? 0.0 : 100.0 * hits / acessos;
    }

    private double calcularTempoMedioFormulaAula(long totalAcessos, long totalHits) {
        if (totalAcessos == 0) return 0.0;

        double h = (double) totalHits / totalAcessos;
        double t1 = hitTime;
        double t2 = tempoLeituraMemoria;
        return t1 + (1.0 - h) * t2;
    }

    private void imprimirRelatorio(Path arquivoAcessos) {
        long total = leituras + escritas;
        long totalHits = hitsLeitura + hitsEscrita;
        double tempoMedio = calcularTempoMedioFormulaAula(total, totalHits);
        System.out.println("=== PARAMETROS DE ENTRADA ===");
        System.out.println("Arquivo: " + arquivoAcessos.toAbsolutePath());
        System.out.println("Politica de escrita: " + (politicaEscrita == PoliticaEscrita.WRITE_BACK ? "write-back" : "write-through"));
        System.out.println("Tamanho da linha: " + tamanhoLinha + " bytes");
        System.out.println("Numero de linhas: " + numeroLinhas);
        System.out.println("Associatividade: " + associatividade);
        System.out.println("Numero de conjuntos: " + numeroConjuntos);
        System.out.printf(Locale.US, "Hit time: %.4f ns%n", hitTime);
        System.out.println("Politica de substituicao: " + (politicaSubstituicao == PoliticaSubstituicao.LRU ? "LRU" : "random"));
        System.out.printf(Locale.US, "Tempo de leitura da memoria principal: %.4f ns%n", tempoLeituraMemoria);
        System.out.printf(Locale.US, "Tempo de escrita da memoria principal: %.4f ns%n", tempoEscritaMemoria);

        System.out.println("\n=== RESULTADOS ===");
        System.out.println("Enderecos de leitura: " + leituras);
        System.out.println("Enderecos de escrita: " + escritas);
        System.out.println("Total de enderecos: " + total);
        System.out.println("Leituras na memoria principal: " + leiturasMemoria);
        System.out.println("Escritas na memoria principal: " + escritasMemoria);
        System.out.printf(Locale.US, "Taxa de hit de leitura: %.4f%% (%d hits)%n", taxaHit(hitsLeitura, leituras), hitsLeitura);
        System.out.printf(Locale.US, "Taxa de hit de escrita: %.4f%% (%d hits)%n", taxaHit(hitsEscrita, escritas), hitsEscrita);
        System.out.printf(Locale.US, "Taxa de hit global: %.4f%% (%d hits)%n", taxaHit(totalHits, total), totalHits);
        System.out.printf(Locale.US, "Tempo medio de acesso: %.4f ns%n", tempoMedio);
    }

    private static boolean ehPotenciaDeDois(int value) {
        return value > 0 && (value & (value - 1)) == 0;
    }

    private static void validar(int tamanhoLinha, int numeroLinhas, int associatividade,
            double hitTime, double tempoLeituraMemoria, double tempoEscritaMemoria) {
        if (!ehPotenciaDeDois(tamanhoLinha)) throw new IllegalArgumentException("O tamanho da linha deve ser potencia de 2.");
        if (!ehPotenciaDeDois(numeroLinhas)) throw new IllegalArgumentException("O numero de linhas deve ser potencia de 2.");
        if (!ehPotenciaDeDois(associatividade) || associatividade > numeroLinhas) {
            throw new IllegalArgumentException("A associatividade deve ser potencia de 2, entre 1 e o numero de linhas.");
        }
        if (hitTime < 0 || tempoLeituraMemoria < 0 || tempoEscritaMemoria < 0) {
            throw new IllegalArgumentException("Os tempos nao podem ser negativos.");
        }
    }

    private static void exibirUso() {
        System.err.println("Nenhum parametro foi informado.");
        System.err.println("Se estiver usando Eclipse, abra Run/Debug Configurations > Arguments");
        System.err.println("e preencha o campo Program arguments com os parametros da simulacao.");
        System.err.println();
        System.err.println("Uso (mesmo tempo de leitura/escrita):");
        System.err.println("  java CacheSimulator <0|1> <tamLinha> <numLinhas> <assoc> <hitNs> <LRU|random> <memNs> <arquivo>");
        System.err.println("Uso (tempos separados):");
        System.err.println("  java CacheSimulator <0|1> <tamLinha> <numLinhas> <assoc> <hitNs> <LRU|random> <leituraNs> <escritaNs> <arquivo>");
    }

    public static void main(String[] argumentos) {
        if (argumentos.length != 8 && argumentos.length != 9) {
            exibirUso();
            System.exit(1);
        }
        try {
            PoliticaEscrita politica;
            if (argumentos[0].equals("0")) politica = PoliticaEscrita.WRITE_THROUGH;
            else if (argumentos[0].equals("1")) politica = PoliticaEscrita.WRITE_BACK;
            else throw new IllegalArgumentException("Politica de escrita deve ser 0 ou 1.");

            int tamanhoLinha = Integer.parseInt(argumentos[1]);
            int numeroLinhas = Integer.parseInt(argumentos[2]);
            int associatividade = Integer.parseInt(argumentos[3]);
            double hitTime = Double.parseDouble(argumentos[4]);
            PoliticaSubstituicao substituicao;
            String nomeSubstituicao = argumentos[5].toUpperCase(Locale.ROOT);
            if (nomeSubstituicao.equals("LRU")) substituicao = PoliticaSubstituicao.LRU;
            else if (nomeSubstituicao.equals("RANDOM") || nomeSubstituicao.equals("ALEATORIA")) substituicao = PoliticaSubstituicao.RANDOM;
            else throw new IllegalArgumentException("Politica de substituicao deve ser LRU ou random.");

            double tempoLeituraMemoria = Double.parseDouble(argumentos[6]);
            double tempoEscritaMemoria = argumentos.length == 9 ? Double.parseDouble(argumentos[7]) : tempoLeituraMemoria;
            Path arquivoAcessos = Path.of(argumentos[argumentos.length - 1]);
            validar(tamanhoLinha, numeroLinhas, associatividade, hitTime, tempoLeituraMemoria, tempoEscritaMemoria);
            if (!Files.isRegularFile(arquivoAcessos)) throw new IllegalArgumentException("Arquivo nao encontrado: " + arquivoAcessos);

            CacheSimulator simulador = new CacheSimulator(politica, tamanhoLinha, numeroLinhas,
                    associatividade, hitTime, substituicao, tempoLeituraMemoria, tempoEscritaMemoria);
            simulador.simular(arquivoAcessos);
            simulador.imprimirRelatorio(arquivoAcessos);
        } catch (IllegalArgumentException | IOException erro) {
            System.err.println("Erro: " + erro.getMessage());
            System.exit(1);
        }
    }
}
