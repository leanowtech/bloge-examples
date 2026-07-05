import AuthorCanvas from './AuthorCanvas';
import Showcase from './Showcase';
import './styles.css';

/** Top-level app shell for the React authoring and showcase routes. */
export default function App() {
  const isShowcaseRoute = window.location.pathname.startsWith('/showcase');

  return (
    <div className="app">
      <header className="topbar">
        <div>
          <p className="eyebrow">BLOGE Visual Canvas</p>
          <h1>{isShowcaseRoute ? 'Showcase' : 'Author'}</h1>
        </div>
        <a className="link" href={isShowcaseRoute ? '/author/' : '/showcase/'}>
          {isShowcaseRoute ? 'Author ->' : 'Showcase ->'}
        </a>
      </header>
      {isShowcaseRoute ? <Showcase /> : <AuthorCanvas />}
    </div>
  );
}
